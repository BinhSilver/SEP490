package com.jlas.cpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Phase 2 communication path (owner 06/11). Content lives in cpath_goal / topic / situation / line /
 * item (seeded); the learner side in cpath_user_*. One MAIN path (in "Hôm nay") and at most one SECONDARY path per
 * learner. A day holds {@code daily_situations} situations; each situation is learned in 4 steps (WORDS, LISTEN,
 * ROLEPLAY, SPEAK); a topic passes when all its situations are done with role-play ≥ 80 % and speaking ≥ 70; a goal
 * ends with an AI call test (kaiwa scenario, overall score ≥ 70), read only from kaiwa's conversation_session.
 * Maps are plain {@code Map}s so the JSON is exactly the API contract of the pages.
 */
@Service
public class CpathService {

    static final List<String> STEPS = List.of("WORDS", "LISTEN", "ROLEPLAY", "SPEAK");
    static final int ROLEPLAY_PASS = 80;
    static final int SPEAK_PASS = 70;
    static final int GOAL_TEST_PASS = 70;
    static final String LEARNER = "リン";
    static final String AUDIO_URL = "/uploads/content-audio/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate db;
    private final com.jlas.jlptpath.service.JpathClock clock;

    public CpathService(JdbcTemplate db, com.jlas.jlptpath.service.JpathClock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Owner 19/10: 2 CP once per situation, on the first time all 4 steps are done. */
    static final int SITUATION_CP = 2;
    static final String REF_SITUATION = "CPATH_SITUATION";
    private org.springframework.beans.factory.ObjectProvider<com.jlas.mission.service.CreditAwardService> awards;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAwards(org.springframework.beans.factory.ObjectProvider<com.jlas.mission.service.CreditAwardService> awards) {
        this.awards = awards;
    }

    /** The date of a DATETIME column value (the driver gives LocalDateTime or Timestamp). */
    static LocalDate dateOf(Object v) {
        if (v instanceof java.time.LocalDateTime t) return t.toLocalDate();
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        return null;
    }

    /** Stable numeric reference of a situation code for the CP ledger (its unique key drops a repeat award). */
    static long situationRef(String code) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /** Queues the situation CP in this transaction and applies the learner's open awards after commit. */
    private int awardSituation(Long userId, String code, String titleVi) {
        com.jlas.mission.service.CreditAwardService credit = awards == null ? null : awards.getIfAvailable();
        if (credit == null) return 0;
        credit.enqueue(userId, SITUATION_CP,
                com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason.STUDY_REWARD, REF_SITUATION,
                situationRef(code), "Hoàn thành tình huống giao tiếp " + (titleVi == null ? code : titleVi), null, null, null);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                credit.applyOpenAwards(userId);
                            } catch (RuntimeException ignored) {
                                // stays queued; the retry job applies it
                            }
                        }
                    });
        }
        return SITUATION_CP;
    }

    // ------------------------------------------------------------------------------------------------- goals

    @Transactional(readOnly = true)
    public List<Map<String, Object>> goals() {
        return db.query("SELECT g.code, g.name_vi, g.fields_json, (SELECT COUNT(*) FROM cpath_topic t WHERE t.goal_code = g.code) n "
                + "FROM cpath_goal g ORDER BY g.sort_order", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goal", rs.getString("code"));
            m.put("nameVi", rs.getString("name_vi"));
            m.put("topicCount", rs.getInt("n"));
            m.put("fields", parse(rs.getString("fields_json"), List.of()));
            return m;
        });
    }

    // -------------------------------------------------------------------------------------------------- path

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> createPath(Long userId, Map<String, Object> req) {
        String goal = str(req.get("goal"));
        Integer exists = db.queryForObject("SELECT COUNT(*) FROM cpath_goal WHERE code = ?", Integer.class, goal);
        if (goal == null || exists == null || exists == 0) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mục tiêu không hợp lệ.");
        String level = str(req.getOrDefault("selfLevel", "BEGINNER"));
        if (!List.of("BEGINNER", "ELEMENTARY", "INTERMEDIATE").contains(level)) level = "BEGINNER";
        int daily = num(req.get("dailySituations")) == 2 ? 2 : 1;
        List<Object> rest = req.get("restDays") instanceof List<?> l ? (List<Object>) l : List.of();
        Object finish = req.get("finishDate");
        Long main = mainPathId(userId);
        String role = main == null ? "MAIN" : "SECONDARY";
        if (main != null) {
            // a second goal replaces the earlier secondary one (owner 06/11: one main + one secondary)
            db.update("UPDATE cpath_user_path SET status = 'PAUSED' WHERE user_id = ? AND role = 'SECONDARY' AND status = 'ACTIVE'", userId);
        }
        db.update("INSERT INTO cpath_user_path (user_id, goal_code, role, status, self_level, fields_json, priority_topics_json, "
                + "daily_situations, rest_days, finish_date) VALUES (?,?,?,?,?,?,?,?,?,?)", userId, goal, role, "ACTIVE", level,
                json(req.get("fields")), json(req.get("priorityTopics")), daily,
                String.join(",", rest.stream().map(String::valueOf).toList()), finish == null || finish.toString().isBlank() ? null : finish.toString());
        Long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("pathId", id, "role", role);
    }

    Long mainPathId(Long userId) {
        List<Long> ids = db.queryForList("SELECT id FROM cpath_user_path WHERE user_id = ? AND role = 'MAIN' AND status = 'ACTIVE' "
                + "ORDER BY id DESC LIMIT 1", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Map<String, Object> pathRow(Long userId, String role) {
        List<Map<String, Object>> rows = db.queryForList("SELECT p.*, g.name_vi FROM cpath_user_path p JOIN cpath_goal g ON g.code = p.goal_code "
                + "WHERE p.user_id = ? AND p.role = ? AND p.status = 'ACTIVE' ORDER BY p.id DESC LIMIT 1", userId, role);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> requirePath(Long userId) {
        Map<String, Object> p = pathRow(userId, "MAIN");
        if (p == null) throw new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình giao tiếp.");
        return p;
    }

    /** The path's situations in study order: priority topics first, then by level and topic order; WORK keeps its fields. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> orderedSituations(Map<String, Object> path) {
        String goal = (String) path.get("goal_code");
        List<Object> priority = parse((String) path.get("priority_topics_json"), List.of());
        Set<String> allowed = null;
        if ("WORK".equals(goal)) {
            List<Object> fields = parse((String) path.get("fields_json"), List.of());
            List<Object> defs = parse(db.queryForObject("SELECT fields_json FROM cpath_goal WHERE code = ?", String.class, goal), List.of());
            allowed = new LinkedHashSet<>();
            for (Object d : defs) {
                Map<String, Object> f = (Map<String, Object>) d;
                if ("OFFICE".equals(f.get("code")) || fields.contains(f.get("code"))) {
                    for (Object c : (List<Object>) f.getOrDefault("topicCodes", List.of())) allowed.add(String.valueOf(c));
                }
            }
            if (allowed.isEmpty()) allowed = null;
        }
        List<Map<String, Object>> rows = db.queryForList("SELECT s.code, s.title_ja, s.title_vi, s.level, s.topic_code, t.name_vi topic_name, "
                + "t.sort_order t_order, s.sort_order s_order FROM cpath_situation s JOIN cpath_topic t ON t.code = s.topic_code "
                + "WHERE t.goal_code = ? ORDER BY t.sort_order, s.sort_order", goal);
        Set<String> keep = allowed;
        List<Map<String, Object>> out = new ArrayList<>(rows.stream().filter(r -> keep == null || keep.contains(r.get("topic_code"))).toList());
        out.sort((a, b) -> {
            int pa = priority.contains(a.get("topic_code")) ? 0 : 1;
            int pb = priority.contains(b.get("topic_code")) ? 0 : 1;
            return pa != pb ? pa - pb : 0; // stable: keeps topic + situation order inside each group
        });
        return out;
    }

    private Map<String, Map<String, Object>> progressOf(Long pathId) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : db.queryForList("SELECT * FROM cpath_user_situation WHERE path_id = ?", pathId)) {
            out.put((String) r.get("situation_code"), r);
        }
        return out;
    }

    private static boolean done(Map<String, Object> p) { return p != null && p.get("done_at") != null; }

    /** Today's situations: the ones planned today, else the next not-done ones (planned now). */
    @Transactional
    public List<Map<String, Object>> today(Long userId, Map<String, Object> path) {
        Long pathId = ((Number) path.get("id")).longValue();
        LocalDate day = clock.today(userId);
        List<Map<String, Object>> order = orderedSituations(path);
        Map<String, Map<String, Object>> prog = progressOf(pathId);
        List<String> planned = new ArrayList<>();
        for (Map<String, Object> s : order) {
            Map<String, Object> p = prog.get(s.get("code"));
            if (p != null && day.equals(toDate(p.get("study_day")))) planned.add((String) s.get("code"));
        }
        if (planned.isEmpty() && !isRest(path, day)) {
            int want = ((Number) path.get("daily_situations")).intValue();
            for (Map<String, Object> s : order) {
                if (planned.size() >= want) break;
                if (!prog.containsKey(s.get("code"))) {
                    db.update("INSERT INTO cpath_user_situation (path_id, situation_code, study_day) VALUES (?,?,?)", pathId, s.get("code"), day);
                    planned.add((String) s.get("code"));
                }
            }
            prog = progressOf(pathId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : order) {
            if (!planned.contains(s.get("code"))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.get("code"));
            m.put("titleVi", s.get("title_vi"));
            m.put("titleJa", s.get("title_ja"));
            m.put("topicCode", s.get("topic_code"));
            m.put("status", done(prog.get(s.get("code"))) ? "DONE" : "TODAY");
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> learnExtra(Long userId) {
        Map<String, Object> path = requirePath(userId);
        Long pathId = ((Number) path.get("id")).longValue();
        Map<String, Map<String, Object>> prog = progressOf(pathId);
        for (Map<String, Object> s : orderedSituations(path)) {
            if (!prog.containsKey(s.get("code"))) {
                db.update("INSERT INTO cpath_user_situation (path_id, situation_code, study_day) VALUES (?,?,?)", pathId, s.get("code"), clock.today(userId));
                return Map.of("id", s.get("code"));
            }
        }
        return Map.of();
    }

    @Transactional
    public Map<String, Object> path(Long userId) {
        Map<String, Object> p = requirePath(userId);
        Long pathId = ((Number) p.get("id")).longValue();
        List<Map<String, Object>> order = orderedSituations(p);
        Map<String, Map<String, Object>> prog = progressOf(pathId);
        long doneCount = order.stream().filter(s -> done(prog.get(s.get("code")))).count();
        Set<String> topics = new LinkedHashSet<>();
        order.forEach(s -> topics.add((String) s.get("topic_code")));
        Integer passed = db.queryForObject("SELECT COUNT(*) FROM cpath_user_topic WHERE path_id = ? AND passed_at IS NOT NULL", Integer.class, pathId);
        String level = (String) p.get("self_level");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pathId", pathId);
        m.put("goal", p.get("goal_code"));
        m.put("nameVi", p.get("name_vi"));
        m.put("selfLevel", level);
        m.put("dailySituations", p.get("daily_situations"));
        m.put("display", Map.of("furigana", !"INTERMEDIATE".equals(level), "romaji", "BEGINNER".equals(level),
                "translation", !"INTERMEDIATE".equals(level)));
        m.put("progress", Map.of("doneSituations", doneCount, "totalSituations", order.size(),
                "passedTopics", passed == null ? 0 : passed, "totalTopics", topics.size()));
        m.put("week", week(userId, p, prog));
        m.put("today", Map.of("situations", today(userId, p), "weeklyTestDay", weeklyTestDay(userId, p)));
        Map<String, Object> sec = pathRow(userId, "SECONDARY");
        m.put("secondary", sec == null ? null : Map.of("goal", sec.get("goal_code"), "nameVi", sec.get("name_vi")));
        return m;
    }

    private boolean isRest(Map<String, Object> path, LocalDate day) {
        String rest = (String) path.get("rest_days");
        if (rest == null || rest.isBlank()) return false;
        String code = day.getDayOfWeek().name().substring(0, 3);
        for (String r : rest.split(",")) if (r.trim().toUpperCase(Locale.ROOT).startsWith(code)) return true;
        return false;
    }

    private List<Map<String, Object>> week(Long userId, Map<String, Object> path, Map<String, Map<String, Object>> prog) {
        LocalDate today = clock.today(userId);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            boolean dayDone = prog.values().stream().anyMatch(p -> d.equals(toDate(p.get("study_day"))) && done(p));
            out.add(Map.of("day", d.toString(), "kind", isRest(path, d) ? "REST" : "STUDY", "done", dayDone, "today", d.equals(today)));
        }
        return out;
    }

    private String weeklyTestDay(Long userId, Map<String, Object> path) {
        LocalDate monday = clock.today(userId).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int i = 6; i >= 0; i--) if (!isRest(path, monday.plusDays(i))) return monday.plusDays(i).toString();
        return null;
    }

    // --------------------------------------------------------------------------------------------------- map

    @Transactional(readOnly = true)
    public Map<String, Object> map(Long userId) {
        Map<String, Object> p = requirePath(userId);
        Long pathId = ((Number) p.get("id")).longValue();
        List<Map<String, Object>> order = orderedSituations(p);
        Map<String, Map<String, Object>> prog = progressOf(pathId);
        Map<String, Map<String, Object>> topicRows = new LinkedHashMap<>();
        for (Map<String, Object> r : db.queryForList("SELECT * FROM cpath_user_topic WHERE path_id = ?", pathId)) topicRows.put((String) r.get("topic_code"), r);
        LocalDate today = clock.today(userId);
        int studyDays = 0;
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int i = 0; i < 7; i++) if (!isRest(p, monday.plusDays(i))) studyDays++;
        int perWeek = Math.max(1, studyDays * ((Number) p.get("daily_situations")).intValue());

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Map<String, Object> s = order.get(i);
            Map<String, Object> pr = prog.get(s.get("code"));
            String status = done(pr) ? (today.equals(toDate(pr.get("study_day"))) ? "TODAY_DONE" : "DONE")
                    : pr != null && today.equals(toDate(pr.get("study_day"))) ? "TODAY" : "NEXT";
            items.add(item("SITUATION", s.get("code"), s.get("title_vi"), s.get("title_ja"), s.get("topic_code"), s.get("topic_name"), status, null));
            boolean lastOfTopic = i == order.size() - 1 || !s.get("topic_code").equals(order.get(i + 1).get("topic_code"));
            if (lastOfTopic) {
                Map<String, Object> t = topicRows.get(s.get("topic_code"));
                items.add(item("TOPIC_TEST", s.get("topic_code"), "Nhập vai: " + s.get("topic_name"), null, s.get("topic_code"),
                        s.get("topic_name"), t != null && t.get("passed_at") != null ? "DONE" : "NEXT", t == null ? null : t.get("speak_best")));
            }
        }
        Map<String, Object> goalTest = lastGoalTest(pathId);
        items.add(item("GOAL_TEST", p.get("goal_code"), "Bài test với AI", null, null, null,
                goalTest != null && Boolean.TRUE.equals(goalTest.get("passed")) ? "DONE" : "NEXT", goalTest == null ? null : goalTest.get("score")));

        // weeks: from the first planned / learned situation on, perWeek situations a week; later weeks are LOCKED
        List<Map<String, Object>> weeks = new ArrayList<>();
        int sit = 0;
        int current = -1;
        List<Map<String, Object>> bucket = new ArrayList<>();
        for (Map<String, Object> it : items) {
            bucket.add(it);
            if ("SITUATION".equals(it.get("type"))) sit++;
            if (("TODAY".equals(it.get("status")) || "TODAY_DONE".equals(it.get("status"))) && current < 0) current = weeks.size();
            if (sit == perWeek) {
                weeks.add(week(weeks.size(), bucket));
                bucket = new ArrayList<>();
                sit = 0;
            }
        }
        if (!bucket.isEmpty()) weeks.add(week(weeks.size(), bucket));
        if (current < 0) current = 0;
        for (int w = current + 1; w < weeks.size(); w++) {
            for (Object o : (List<?>) weeks.get(w).get("items")) {
                @SuppressWarnings("unchecked") Map<String, Object> it = (Map<String, Object>) o;
                if ("NEXT".equals(it.get("status"))) it.put("status", "LOCKED");
            }
        }
        LocalDate start = monday.minusWeeks(current);
        for (int w = 0; w < weeks.size(); w++) {
            weeks.get(w).put("from", start.plusWeeks(w).toString());
            weeks.get(w).put("to", start.plusWeeks(w).plusDays(6).toString());
        }
        return Map.of("weeks", weeks, "currentWeek", current);
    }

    private static Map<String, Object> week(int index, List<Map<String, Object>> items) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("index", index + 1);
        w.put("items", items);
        return w;
    }

    private static Map<String, Object> item(String type, Object id, Object titleVi, Object titleJa, Object topic, Object topicName,
                                            String status, Object score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("titleVi", titleVi);
        m.put("titleJa", titleJa);
        m.put("topicCode", topic);
        m.put("topicNameVi", topicName);
        m.put("status", status);
        m.put("score", score);
        return m;
    }

    // --------------------------------------------------------------------------------------------- situation

    @Transactional(readOnly = true)
    public Map<String, Object> situation(Long userId, String code) {
        List<Map<String, Object>> rows = db.queryForList("SELECT s.*, t.name_vi topic_name, t.goal_code FROM cpath_situation s "
                + "JOIN cpath_topic t ON t.code = s.topic_code WHERE s.code = ?", code);
        if (rows.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy tình huống.");
        Map<String, Object> s = rows.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", code);
        m.put("titleJa", s.get("title_ja"));
        m.put("titleVi", s.get("title_vi"));
        m.put("situationVi", s.get("situation_vi"));
        m.put("level", s.get("level"));
        m.put("topic", Map.of("code", s.get("topic_code"), "nameVi", s.get("topic_name")));
        String topic = (String) s.get("topic_code");
        m.put("words", db.query("SELECT * FROM cpath_item WHERE topic_code = ? AND kind = 'WORD' ORDER BY sort_order", (rs, i) -> {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", rs.getString("code"));
            w.put("word", rs.getString("ja"));
            w.put("reading", rs.getString("reading"));
            w.put("meaningVi", rs.getString("vi"));
            w.put("audioUrl", url(rs.getString("audio_path")));
            return w;
        }, topic));
        m.put("phrases", db.query("SELECT * FROM cpath_item WHERE kind = 'PHRASE' AND situation_code = ? ORDER BY sort_order", (rs, i) -> {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", rs.getString("code"));
            w.put("ja", rs.getString("ja"));
            w.put("reading", rs.getString("reading"));
            w.put("vi", rs.getString("vi"));
            w.put("note", rs.getString("note_vi"));
            w.put("audioUrl", url(rs.getString("audio_path")));
            return w;
        }, code));
        m.put("lines", db.query("SELECT * FROM cpath_line WHERE situation_code = ? ORDER BY line_order", (rs, i) -> {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("order", rs.getInt("line_order"));
            l.put("speaker", rs.getString("speaker"));
            l.put("ja", rs.getString("ja"));
            l.put("furigana", rs.getString("furigana"));
            l.put("vi", rs.getString("vi"));
            l.put("audioUrl", url(rs.getString("audio_path")));
            l.put("learner", rs.getBoolean("learner"));
            return l;
        }, code));
        @SuppressWarnings("unchecked") List<Map<String, Object>> lineList = (List<Map<String, Object>>) m.get("lines");
        m.put("challenge", challengeOf((String) s.get("challenge_json"), lineList));
        Long main = mainPathId(userId);
        Map<String, Object> prog = main == null ? null : progressOf(main).get(code);
        Map<String, Object> steps = new LinkedHashMap<>();
        String doneSteps = prog == null ? "" : String.valueOf(prog.get("steps_done"));
        for (String st : STEPS) steps.put(st.toLowerCase(Locale.ROOT), doneSteps.contains(st));
        m.put("steps", steps);
        m.put("aiCallUrl", scenarioUrl((String) s.get("scenario_slug")));
        return m;
    }

    /** Role-play turns from the seeded challenge: the learner's line plus two distractors, shuffled by line order. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> challengeOf(String json, List<Map<String, Object>> lines) {
        Map<String, Object> c = parse(json, Map.of());
        List<Map<String, Object>> turns = new ArrayList<>();
        for (Object o : (List<Object>) c.getOrDefault("turns", List.of())) {
            Map<String, Object> t = (Map<String, Object>) o;
            int line = num(t.get("line"));
            Map<String, Object> right = lines.stream().filter(l -> ((Number) l.get("order")).intValue() == line).findFirst().orElse(null);
            if (right == null) continue;
            List<Map<String, Object>> options = new ArrayList<>();
            options.add(Map.of("ja", right.get("ja"), "vi", right.get("vi") == null ? "" : right.get("vi")));
            for (Object d : (List<Object>) t.getOrDefault("distractors", List.of())) {
                Map<String, Object> dm = (Map<String, Object>) d;
                options.add(Map.of("ja", String.valueOf(dm.get("ja")), "vi", String.valueOf(dm.getOrDefault("vi", ""))));
            }
            int answer = Math.floorMod(line * 7, options.size());
            Map<String, Object> first = options.remove(0);
            options.add(answer, first);
            turns.add(Map.of("line", line, "options", options, "answerIndex", answer, "whyVi", String.valueOf(t.getOrDefault("why_vi", ""))));
        }
        return Map.of("turns", turns);
    }

    @Transactional
    public Map<String, Object> step(Long userId, String code, String step, Map<String, Object> body) {
        String st = step == null ? "" : step.toUpperCase(Locale.ROOT);
        if (!STEPS.contains(st)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bước không hợp lệ.");
        Long pathId = mainPathId(userId);
        if (pathId == null) throw new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình giao tiếp.");
        db.update("INSERT IGNORE INTO cpath_user_situation (path_id, situation_code, study_day) VALUES (?,?,?)", pathId, code, clock.today(userId));
        Map<String, Object> row = progressOf(pathId).get(code);
        Set<String> doneSteps = new LinkedHashSet<>();
        for (String x : String.valueOf(row.get("steps_done")).split(",")) if (!x.isBlank()) doneSteps.add(x);
        doneSteps.add(st);
        db.update("UPDATE cpath_user_situation SET steps_done = ? WHERE path_id = ? AND situation_code = ?",
                String.join(",", STEPS.stream().filter(doneSteps::contains).toList()), pathId, code);
        if ("ROLEPLAY".equals(st) && body != null) {
            db.update("UPDATE cpath_user_situation SET roleplay_correct = ?, roleplay_total = ? WHERE path_id = ? AND situation_code = ?",
                    num(body.get("correct")), num(body.get("total")), pathId, code);
        }
        if ("SPEAK".equals(st) && body != null && body.get("score") != null) {
            db.update("UPDATE cpath_user_situation SET speak_best = GREATEST(COALESCE(speak_best, 0), ?) WHERE path_id = ? AND situation_code = ?",
                    num(body.get("score")), pathId, code);
        }
        boolean all = doneSteps.containsAll(STEPS);
        int cp = 0;
        if (all) {
            if (row.get("done_at") == null) {
                String title = db.queryForList("SELECT title_vi FROM cpath_situation WHERE code = ?", String.class, code)
                        .stream().findFirst().orElse(null);
                cp = awardSituation(userId, code, title);
            }
            db.update("UPDATE cpath_user_situation SET done_at = COALESCE(done_at, CURRENT_TIMESTAMP(6)) WHERE path_id = ? AND situation_code = ?", pathId, code);
            updateTopic(pathId, code);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> steps = new LinkedHashMap<>();
        for (String x : STEPS) steps.put(x.toLowerCase(Locale.ROOT), doneSteps.contains(x));
        out.put("steps", steps);
        out.put("situationDone", all);
        out.put("cp", cp);
        return out;
    }

    /** A topic passes when all its situations are done, role-play ≥ 80 % overall and speaking ≥ 70 on average. */
    private void updateTopic(Long pathId, String situation) {
        String topic = db.queryForObject("SELECT topic_code FROM cpath_situation WHERE code = ?", String.class, situation);
        List<Map<String, Object>> rows = db.queryForList("SELECT s.code, u.done_at, u.roleplay_correct, u.roleplay_total, u.speak_best "
                + "FROM cpath_situation s LEFT JOIN cpath_user_situation u ON u.situation_code = s.code AND u.path_id = ? WHERE s.topic_code = ?",
                pathId, topic);
        int correct = 0, total = 0, speakSum = 0, speakN = 0;
        boolean allDone = true;
        for (Map<String, Object> r : rows) {
            if (r.get("done_at") == null) allDone = false;
            if (r.get("roleplay_total") != null) { correct += num(r.get("roleplay_correct")); total += num(r.get("roleplay_total")); }
            if (r.get("speak_best") != null) { speakSum += num(r.get("speak_best")); speakN++; }
        }
        Integer quiz = total == 0 ? null : Math.round(100f * correct / total);
        Integer speak = speakN == 0 ? null : Math.round((float) speakSum / speakN);
        boolean passed = allDone && quiz != null && quiz >= ROLEPLAY_PASS && speak != null && speak >= SPEAK_PASS;
        db.update("INSERT INTO cpath_user_topic (path_id, topic_code, quiz_best, speak_best, passed_at) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE quiz_best = VALUES(quiz_best), speak_best = VALUES(speak_best), "
                + "passed_at = COALESCE(passed_at, VALUES(passed_at))", pathId, topic, quiz, speak,
                passed ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null);
    }

    // -------------------------------------------------------------------------------------------------- day

    @Transactional(readOnly = true)
    public Map<String, Object> day(Long userId, LocalDate day) {
        Map<String, Object> p = requirePath(userId);
        Long pathId = ((Number) p.get("id")).longValue();
        List<Map<String, Object>> rows = db.queryForList("SELECT u.*, s.title_vi, s.title_ja, s.topic_code FROM cpath_user_situation u "
                + "JOIN cpath_situation s ON s.code = u.situation_code WHERE u.path_id = ? AND u.study_day = ? ORDER BY s.sort_order", pathId, day);
        // Shape read by cpath-day.js: status DONE|TODAY, steps {words, listen, roleplay, speak}, topicNameVi, score;
        // scores = one row per scored part of a situation {labelVi, score, noteVi}.
        Map<String, String> topicNames = new LinkedHashMap<>();
        for (Map<String, Object> t : db.queryForList("SELECT code, name_vi FROM cpath_topic")) {
            topicNames.put(String.valueOf(t.get("code")), t.get("name_vi") == null ? null : String.valueOf(t.get("name_vi")));
        }
        List<Map<String, Object>> situations = new ArrayList<>();
        List<Map<String, Object>> scores = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Set<String> done = new LinkedHashSet<>(List.of(String.valueOf(r.get("steps_done")).split(",")));
            Map<String, Object> steps = new LinkedHashMap<>();
            for (String x : STEPS) steps.put(x.toLowerCase(Locale.ROOT), done.contains(x));
            Integer roleplayPct = r.get("roleplay_total") == null || num(r.get("roleplay_total")) == 0 ? null
                    : Math.round(100f * num(r.get("roleplay_correct")) / num(r.get("roleplay_total")));
            Integer speak = r.get("speak_best") == null ? null : num(r.get("speak_best"));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", r.get("situation_code"));
            s.put("titleVi", String.valueOf(r.get("title_vi")));
            s.put("topicCode", r.get("topic_code"));
            s.put("topicNameVi", topicNames.get(String.valueOf(r.get("topic_code"))));
            s.put("status", r.get("done_at") != null ? "DONE" : "TODAY");
            s.put("steps", steps);
            s.put("score", speak != null ? speak : roleplayPct);
            situations.add(s);
            String title = String.valueOf(r.get("title_vi"));
            if (roleplayPct != null) {
                scores.add(scoreRow("Nhập vai · " + title, roleplayPct,
                        num(r.get("roleplay_correct")) + "/" + num(r.get("roleplay_total")) + " lượt trả lời đúng"));
            }
            if (speak != null) {
                scores.add(scoreRow("Luyện nói · " + title, speak, "Điểm phát âm cao nhất"));
            }
        }
        // CP of the day: the situations first finished on that day (2 CP each, owner 19/10)
        long finished = rows.stream().filter(r -> r.get("done_at") != null
                && day.equals(dateOf(r.get("done_at")))).count();
        return Map.of("day", day.toString(), "situations", situations, "scores", scores,
                "cp", (int) finished * SITUATION_CP);
    }

    private static Map<String, Object> scoreRow(String labelVi, int score, String noteVi) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labelVi", labelVi);
        m.put("score", score);
        m.put("noteVi", noteVi);
        return m;
    }

    // --------------------------------------------------------------------------------------------- goal test

    private Map<String, Object> lastGoalTest(Long pathId) {
        List<Map<String, Object>> r = db.queryForList("SELECT * FROM cpath_user_goal_test WHERE path_id = ? ORDER BY taken_at DESC LIMIT 1", pathId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** The goal's AI call test: where to take it, the last result, and when a retry opens (24 h after a fail). */
    @Transactional(readOnly = true)
    public Map<String, Object> goalTest(Long userId) {
        Map<String, Object> p = requirePath(userId);
        String slug = db.queryForObject("SELECT test_scenario_slug FROM cpath_goal WHERE code = ?", String.class, p.get("goal_code"));
        Map<String, Object> last = lastGoalTest(((Number) p.get("id")).longValue());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarioUrl", scenarioUrl(slug));
        m.put("passScore", GOAL_TEST_PASS);
        m.put("lastScore", last == null ? null : last.get("score"));
        m.put("passed", last != null && Boolean.TRUE.equals(last.get("passed")));
        m.put("retryAt", last != null && !Boolean.TRUE.equals(last.get("passed")) && last.get("taken_at") != null
                ? ((java.sql.Timestamp) last.get("taken_at")).toLocalDateTime().plusHours(24).toString() : null);
        return m;
    }

    /** Reads the learner's newest scored kaiwa session on the goal's test scenario (read only) and records it. */
    @Transactional
    public Map<String, Object> checkGoalTest(Long userId) {
        Map<String, Object> p = requirePath(userId);
        Long pathId = ((Number) p.get("id")).longValue();
        String slug = db.queryForObject("SELECT test_scenario_slug FROM cpath_goal WHERE code = ?", String.class, p.get("goal_code"));
        List<Map<String, Object>> rows = slug == null ? List.of() : db.queryForList("SELECT cs.id, cs.overall_score FROM conversation_session cs "
                + "JOIN scenario sc ON sc.id = cs.scenario_id WHERE cs.user_id = ? AND sc.slug = ? AND cs.overall_score IS NOT NULL "
                + "ORDER BY cs.started_at DESC LIMIT 1", userId, slug);
        if (rows.isEmpty()) return goalTest(userId);
        Long session = ((Number) rows.get(0).get("id")).longValue();
        Integer seen = db.queryForObject("SELECT COUNT(*) FROM cpath_user_goal_test WHERE path_id = ? AND conversation_session_id = ?",
                Integer.class, pathId, session);
        if (seen == null || seen == 0) {
            int score = ((Number) rows.get(0).get("overall_score")).intValue();
            db.update("INSERT INTO cpath_user_goal_test (path_id, conversation_session_id, score, passed) VALUES (?,?,?,?)",
                    pathId, session, score, score >= GOAL_TEST_PASS);
            if (score >= GOAL_TEST_PASS) db.update("UPDATE cpath_user_path SET status = 'DONE' WHERE id = ?", pathId);
        }
        return goalTest(userId);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private String scenarioUrl(String slug) {
        if (slug == null) return null;
        List<Long> ids = db.queryForList("SELECT id FROM scenario WHERE slug = ? AND is_active = 1", Long.class, slug);
        return ids.isEmpty() ? null : "/scenario/" + ids.get(0);
    }

    private static String url(String path) { return path == null || path.isBlank() ? null : AUDIO_URL + path; }

    private static String str(Object o) { return o == null ? null : o.toString().trim().toUpperCase(Locale.ROOT); }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? 0 : Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static LocalDate toDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return o == null ? null : LocalDate.parse(o.toString().substring(0, 10));
    }

    private static String json(Object o) {
        try { return o == null ? null : JSON.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T parse(String json, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try { return (T) JSON.readValue(json, Object.class); } catch (Exception e) { return fallback; }
    }
}
