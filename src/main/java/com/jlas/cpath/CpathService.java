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

    static java.time.LocalDateTime dateTimeOf(Object v) {
        if (v instanceof java.time.LocalDateTime t) return t;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return java.time.LocalDateTime.now();
    }

    /** Stable numeric reference of a situation code for the CP ledger (its unique key drops a repeat award). */
    static long situationRef(String code) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /** Owner 06/11 rewards like JLPT: a whole study day, a passed topic (the big one), a passed weekly test. */
    static final int DAY_CP = 2;
    static final int TOPIC_CP = 5;
    static final int WEEK_CP = 2;
    static final String REF_DAY = "CPATH_DAY";
    static final String REF_TOPIC = "CPATH_TOPIC";
    static final String REF_WEEK = "CPATH_WEEK";

    private int awardSituation(Long userId, String code, String titleVi) {
        return award(userId, SITUATION_CP, REF_SITUATION, situationRef(code),
                "Hoàn thành tình huống giao tiếp " + (titleVi == null ? code : titleVi));
    }

    /** Queues CP in this transaction (the ledger key drops a repeat) and applies the open awards after commit. */
    private int award(Long userId, int amount, String refType, long refId, String note) {
        com.jlas.mission.service.CreditAwardService credit = awards == null ? null : awards.getIfAvailable();
        if (credit == null) return 0;
        credit.enqueue(userId, amount,
                com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason.STUDY_REWARD, refType,
                refId, note, null, null, null);
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
        return amount;
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

    /** The active path (main or secondary) whose goal holds the situation; the main path otherwise. */
    Long pathIdForSituation(Long userId, String code) {
        List<Long> ids = db.queryForList("SELECT p.id FROM cpath_user_path p JOIN cpath_topic t ON t.goal_code = p.goal_code "
                + "JOIN cpath_situation s ON s.topic_code = t.code WHERE p.user_id = ? AND p.status = 'ACTIVE' AND s.code = ? "
                + "ORDER BY p.role = 'MAIN' DESC, p.id DESC LIMIT 1", Long.class, userId, code);
        return ids.isEmpty() ? mainPathId(userId) : ids.get(0);
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
                // the office field is always in (owner 06/11); its code may carry a prefix (JLAS-WORK-INDUSTRY-OFFICE)
                if (String.valueOf(f.get("code")).endsWith("OFFICE") || fields.contains(f.get("code"))) {
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
        if (sec == null) {
            m.put("secondary", null);
        } else {
            // the secondary goal is free study (owner 31/10): its progress and the next situation to open
            Map<String, Map<String, Object>> secProg = progressOf(((Number) sec.get("id")).longValue());
            List<Map<String, Object>> secOrder = orderedSituations(sec);
            Map<String, Object> next = secOrder.stream().filter(x -> !done(secProg.get(x.get("code")))).findFirst().orElse(null);
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("goal", sec.get("goal_code"));
            sm.put("nameVi", sec.get("name_vi"));
            sm.put("doneSituations", secOrder.stream().filter(x -> done(secProg.get(x.get("code")))).count());
            sm.put("totalSituations", secOrder.size());
            sm.put("next", next == null ? null : Map.of("id", next.get("code"), "titleVi", String.valueOf(next.get("title_vi"))));
            m.put("secondary", sm);
        }
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
                Map<String, Object> node = item("TOPIC_TEST", s.get("topic_code"), "Nhập vai: " + s.get("topic_name"), null, s.get("topic_code"),
                        s.get("topic_name"), t != null && t.get("passed_at") != null ? "DONE" : "NEXT", t == null ? null : t.get("speak_best"));
                node.put("targetSituation", weakestSituation(order, prog, (String) s.get("topic_code")));
                node.put("quizBest", t == null ? null : t.get("quiz_best"));
                items.add(node);
            }
        }
        Map<String, Object> goalTest = lastGoalTest(pathId);
        Map<String, Object> goalNode = item("GOAL_TEST", p.get("goal_code"), "Bài test với AI", null, null, null,
                goalTest != null && Boolean.TRUE.equals(goalTest.get("passed")) ? "DONE" : "NEXT", goalTest == null ? null : goalTest.get("score"));
        goalNode.put("url", scenarioUrl(db.queryForList("SELECT test_scenario_slug FROM cpath_goal WHERE code = ?", String.class,
                p.get("goal_code")).stream().findFirst().orElse(null)));
        Map<String, Object> gt = goalTest(userId);
        goalNode.put("retryAt", gt.get("retryAt"));
        goalNode.put("weakTopics", gt.get("weakTopics"));
        goalNode.put("certificateUrl", gt.get("certificateUrl"));
        items.add(goalNode);

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

    /**
     * The situation of a topic to practise for the topic check: the first one not done, else the one with the lowest
     * speaking (no score counts lowest), else the lowest role-play.
     */
    private static String weakestSituation(List<Map<String, Object>> order, Map<String, Map<String, Object>> prog, String topic) {
        String best = null;
        long bestKey = Long.MAX_VALUE;
        for (Map<String, Object> s : order) {
            if (!topic.equals(s.get("topic_code"))) continue;
            Map<String, Object> p = prog.get(s.get("code"));
            if (!done(p)) return (String) s.get("code");
            long speak = p.get("speak_best") == null ? -1 : num(p.get("speak_best"));
            long rp = p.get("roleplay_total") == null || num(p.get("roleplay_total")) == 0 ? 0
                    : 100L * num(p.get("roleplay_correct")) / num(p.get("roleplay_total"));
            long key = speak * 1000 + rp;
            if (key < bestKey) { bestKey = key; best = (String) s.get("code"); }
        }
        return best;
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
        Long pathId = pathIdForSituation(userId, code);
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
            boolean first = row.get("done_at") == null;
            db.update("UPDATE cpath_user_situation SET done_at = COALESCE(done_at, CURRENT_TIMESTAMP(6)) WHERE path_id = ? AND situation_code = ?", pathId, code);
            String passedTopic = updateTopic(pathId, code);
            if (passedTopic != null) {
                cp += award(userId, TOPIC_CP, REF_TOPIC, situationRef(passedTopic), "Đạt chủ đề giao tiếp " + passedTopic);
            }
            if (first) {
                // the whole day: every situation planned for today is done
                LocalDate today = clock.today(userId);
                Integer open = db.queryForObject("SELECT COUNT(*) FROM cpath_user_situation WHERE path_id = ? AND study_day = ? AND done_at IS NULL",
                        Integer.class, pathId, today);
                if (open != null && open == 0) {
                    cp += award(userId, DAY_CP, REF_DAY, today.toEpochDay(), "Xong ngày học giao tiếp " + today);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> steps = new LinkedHashMap<>();
        for (String x : STEPS) steps.put(x.toLowerCase(Locale.ROOT), doneSteps.contains(x));
        out.put("steps", steps);
        out.put("situationDone", all);
        out.put("cp", cp);
        return out;
    }

    /**
     * A topic passes when all its situations are done, role-play ≥ 80 % overall and speaking ≥ 70 on average.
     * Returns the topic code when this call passed it for the first time (its CP), else null.
     */
    private String updateTopic(Long pathId, String situation) {
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
        Integer before = db.queryForObject("SELECT COUNT(*) FROM cpath_user_topic WHERE path_id = ? AND topic_code = ? AND passed_at IS NOT NULL",
                Integer.class, pathId, topic);
        db.update("INSERT INTO cpath_user_topic (path_id, topic_code, quiz_best, speak_best, passed_at) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE quiz_best = VALUES(quiz_best), speak_best = VALUES(speak_best), "
                + "passed_at = COALESCE(passed_at, VALUES(passed_at))", pathId, topic, quiz, speak,
                passed ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null);
        return passed && (before == null || before == 0) ? topic : null;
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
        boolean dayDone = !rows.isEmpty() && rows.stream().allMatch(r -> r.get("done_at") != null);
        return Map.of("day", day.toString(), "situations", situations, "scores", scores,
                "cp", (int) finished * SITUATION_CP + (dayDone && finished > 0 ? DAY_CP : 0));
    }

    private static Map<String, Object> scoreRow(String labelVi, int score, String noteVi) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labelVi", labelVi);
        m.put("score", score);
        m.put("noteVi", noteVi);
        return m;
    }

    // ------------------------------------------------------------------------------------------- weekly test

    static final int WEEKLY_MAX = 10;
    static final int WEEKLY_PASS = 80;

    /** One weekly question: the Japanese of a word, phrase or learner line, 4 Vietnamese meanings. */
    private record WeeklyQ(String id, String ja, String reading, String audio, List<String> options, int answer) {
    }

    /**
     * Owner 06/11 weekly test, like the JLPT path: up to 10 questions on what was studied this week (words and
     * phrases of the week's situations, learner lines of situations spoken under 70 first). Opens on the week's test
     * day; ≥ 80% gives the week's CP once. The questions are rebuilt from a seed of user + week, never stored.
     */
    private List<WeeklyQ> weeklyQuestions(Long userId, Map<String, Object> path, LocalDate monday) {
        Long pathId = ((Number) path.get("id")).longValue();
        LocalDate today = clock.today(userId);
        List<Map<String, Object>> studied = db.queryForList("SELECT u.situation_code, u.speak_best, s.topic_code FROM cpath_user_situation u "
                + "JOIN cpath_situation s ON s.code = u.situation_code WHERE u.path_id = ? AND u.study_day BETWEEN ? AND ? "
                + "AND u.steps_done <> ''", pathId, monday, today);
        if (studied.isEmpty()) return List.of();
        List<String> sits = studied.stream().map(r -> (String) r.get("situation_code")).toList();
        Set<String> topics = new LinkedHashSet<>(studied.stream().map(r -> (String) r.get("topic_code")).toList());
        String in = String.join(",", java.util.Collections.nCopies(sits.size(), "?"));
        String tin = String.join(",", java.util.Collections.nCopies(topics.size(), "?"));
        List<Object> args = new ArrayList<>(sits);
        args.addAll(topics);
        List<Map<String, Object>> pool = new ArrayList<>(db.queryForList("SELECT code id, ja, reading, vi, audio_path FROM cpath_item "
                + "WHERE vi IS NOT NULL AND (situation_code IN (" + in + ") OR (kind = 'WORD' AND topic_code IN (" + tin + ")))", args.toArray()));
        // learner lines of situations spoken under 70 (or not scored) come first
        List<String> weak = studied.stream().filter(r -> r.get("speak_best") == null || num(r.get("speak_best")) < SPEAK_PASS)
                .map(r -> (String) r.get("situation_code")).toList();
        List<Map<String, Object>> lines = new ArrayList<>();
        if (!weak.isEmpty()) {
            String win = String.join(",", java.util.Collections.nCopies(weak.size(), "?"));
            lines.addAll(db.queryForList("SELECT CONCAT(situation_code, '-L', line_order) id, ja, NULL reading, vi, audio_path FROM cpath_line "
                    + "WHERE learner = 1 AND vi IS NOT NULL AND situation_code IN (" + win + ")", weak.toArray()));
        }
        java.util.Random rnd = new java.util.Random(userId * 31 + monday.toEpochDay());
        java.util.Collections.shuffle(pool, rnd);
        java.util.Collections.shuffle(lines, rnd);
        List<Map<String, Object>> asked = new ArrayList<>(lines.subList(0, Math.min(3, lines.size())));
        for (Map<String, Object> m : pool) {
            if (asked.size() >= WEEKLY_MAX) break;
            asked.add(m);
        }
        // distractors of the same kind: a sentence among sentences (other learner lines of the goal), a word among words
        List<String> itemVi = new ArrayList<>();
        for (Map<String, Object> m : pool) itemVi.add(String.valueOf(m.get("vi")));
        List<String> lineVi = lines.isEmpty() ? List.of() : db.queryForList("SELECT l.vi FROM cpath_line l JOIN cpath_situation s ON s.code = l.situation_code "
                + "JOIN cpath_topic t ON t.code = s.topic_code WHERE l.learner = 1 AND l.vi IS NOT NULL AND t.goal_code = ?", String.class, path.get("goal_code"));
        Set<Object> lineIds = new java.util.HashSet<>();
        for (Map<String, Object> m : lines) lineIds.add(m.get("id"));
        List<WeeklyQ> out = new ArrayList<>();
        for (Map<String, Object> m : asked) {
            String right = String.valueOf(m.get("vi"));
            List<String> opts = new ArrayList<>();
            opts.add(right);
            List<String> others = new ArrayList<>(new LinkedHashSet<>(lineIds.contains(m.get("id")) ? lineVi : itemVi));
            java.util.Collections.shuffle(others, rnd);
            for (String o : others) {
                if (opts.size() >= 4) break;
                if (!o.equalsIgnoreCase(right) && opts.stream().noneMatch(x -> x.equalsIgnoreCase(o))) opts.add(o);
            }
            if (opts.size() < 2) continue;
            java.util.Collections.shuffle(opts, rnd);
            out.add(new WeeklyQ(String.valueOf(m.get("id")), String.valueOf(m.get("ja")),
                    m.get("reading") == null ? null : String.valueOf(m.get("reading")), url((String) m.get("audio_path")),
                    opts, opts.indexOf(right)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- daily review

    /** Days after a situation's first completion on which its words and phrases come back (spaced review). */
    static final List<Integer> REVIEW_DAYS = List.of(1, 3, 7, 14, 30);
    static final int REVIEW_MAX = 10;

    /**
     * Owner 06/11 "từ và mẫu câu vào lượt ôn": today's review = the Sổ tay entries first, then the words and phrases
     * of situations finished 1, 3, 7, 14 or 30 days ago. Same question shape as the weekly test; a miss goes to the
     * Sổ tay, a right answer takes it out. Not counted in progress, no CP.
     */
    private List<WeeklyQ> reviewQuestions(Long userId, Map<String, Object> path) {
        Long pathId = ((Number) path.get("id")).longValue();
        LocalDate today = clock.today(userId);
        List<Map<String, Object>> asked = new ArrayList<>();
        asked.addAll(db.queryForList("SELECT i.code id, i.ja, i.reading, i.vi, i.audio_path FROM cpath_user_note n JOIN cpath_item i "
                + "ON i.code = n.item_code WHERE n.path_id = ? AND n.kind = 'ITEM' AND i.vi IS NOT NULL ORDER BY n.wrong_count DESC", pathId));
        List<Map<String, Object>> noteLines = db.queryForList("SELECT CONCAT(l.situation_code, '-L', l.line_order) id, l.ja, NULL reading, l.vi, l.audio_path "
                + "FROM cpath_user_note n JOIN cpath_line l ON CONCAT(l.situation_code, '-L', l.line_order) = n.item_code "
                + "WHERE n.path_id = ? AND n.kind = 'LINE' AND l.vi IS NOT NULL", pathId);
        asked.addAll(noteLines);
        List<String> due = new ArrayList<>();
        for (Map<String, Object> r : db.queryForList("SELECT situation_code, done_at FROM cpath_user_situation WHERE path_id = ? AND done_at IS NOT NULL", pathId)) {
            LocalDate d = dateOf(r.get("done_at"));
            if (d != null && REVIEW_DAYS.contains((int) java.time.temporal.ChronoUnit.DAYS.between(d, today))) due.add((String) r.get("situation_code"));
        }
        if (!due.isEmpty()) {
            String in = String.join(",", java.util.Collections.nCopies(due.size(), "?"));
            List<Map<String, Object>> fresh = new ArrayList<>(db.queryForList("SELECT i.code id, i.ja, i.reading, i.vi, i.audio_path FROM cpath_item i "
                    + "WHERE i.vi IS NOT NULL AND (i.situation_code IN (" + in + ") OR (i.kind = 'WORD' AND i.topic_code IN "
                    + "(SELECT topic_code FROM cpath_situation WHERE code IN (" + in + "))))", java.util.stream.Stream.concat(due.stream(), due.stream()).toArray()));
            java.util.Collections.shuffle(fresh, new java.util.Random(userId * 17 + today.toEpochDay()));
            Set<Object> seen = new java.util.HashSet<>();
            for (Map<String, Object> m : asked) seen.add(m.get("id"));
            for (Map<String, Object> m : fresh) if (seen.add(m.get("id"))) asked.add(m);
        }
        if (asked.size() > REVIEW_MAX) asked = new ArrayList<>(asked.subList(0, REVIEW_MAX));
        if (asked.isEmpty()) return List.of();
        List<String> itemVi = db.queryForList("SELECT i.vi FROM cpath_item i JOIN cpath_topic t ON t.code = i.topic_code WHERE t.goal_code = ? AND i.vi IS NOT NULL",
                String.class, path.get("goal_code"));
        List<String> lineVi = db.queryForList("SELECT l.vi FROM cpath_line l JOIN cpath_situation s ON s.code = l.situation_code "
                + "JOIN cpath_topic t ON t.code = s.topic_code WHERE l.learner = 1 AND l.vi IS NOT NULL AND t.goal_code = ?", String.class, path.get("goal_code"));
        java.util.Random rnd = new java.util.Random(userId * 13 + today.toEpochDay());
        List<WeeklyQ> out = new ArrayList<>();
        for (Map<String, Object> m : asked) {
            String right = String.valueOf(m.get("vi"));
            boolean line = String.valueOf(m.get("id")).matches(".*-L\\d+$");
            List<String> others = new ArrayList<>(new LinkedHashSet<>(line ? lineVi : itemVi));
            java.util.Collections.shuffle(others, rnd);
            List<String> opts = new ArrayList<>();
            opts.add(right);
            for (String o : others) {
                if (opts.size() >= 4) break;
                if (!o.equalsIgnoreCase(right) && opts.stream().noneMatch(x -> x.equalsIgnoreCase(o))) opts.add(o);
            }
            if (opts.size() < 2) continue;
            java.util.Collections.shuffle(opts, rnd);
            out.add(new WeeklyQ(String.valueOf(m.get("id")), String.valueOf(m.get("ja")),
                    m.get("reading") == null ? null : String.valueOf(m.get("reading")), url((String) m.get("audio_path")), opts, opts.indexOf(right)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> review(Long userId) {
        Map<String, Object> path = requirePath(userId);
        List<WeeklyQ> qs = reviewQuestions(userId, path);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", true);
        m.put("passScore", WEEKLY_PASS);
        m.put("questions", qs.stream().map(q -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", q.id());
            x.put("ja", q.ja());
            x.put("reading", q.reading());
            x.put("audioUrl", q.audio());
            x.put("options", q.options());
            return x;
        }).toList());
        return m;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> submitReview(Long userId, Map<String, Object> body) {
        Map<String, Object> path = requirePath(userId);
        Long pid = ((Number) path.get("id")).longValue();
        List<WeeklyQ> qs = reviewQuestions(userId, path);
        if (qs.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Hôm nay chưa có mục cần ôn.");
        Map<String, Object> answers = body != null && body.get("answers") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        int correct = 0;
        List<Map<String, Object>> review = new ArrayList<>();
        for (WeeklyQ q : qs) {
            Object given = answers.get(q.id());
            boolean ok = given != null && num(given) == q.answer();
            if (ok) {
                correct++;
                db.update("DELETE FROM cpath_user_note WHERE path_id = ? AND item_code = ?", pid, q.id());
            } else {
                db.update("INSERT INTO cpath_user_note (path_id, item_code, kind) VALUES (?,?,?) ON DUPLICATE KEY UPDATE wrong_count = wrong_count + 1",
                        pid, q.id(), q.id().matches(".*-L\\d+$") ? "LINE" : "ITEM");
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", q.id());
            r.put("ja", q.ja());
            r.put("correct", ok);
            r.put("answer", q.options().get(q.answer()));
            review.add(r);
        }
        int score = Math.round(100f * correct / qs.size());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", score);
        m.put("correct", correct);
        m.put("total", qs.size());
        m.put("passed", score >= WEEKLY_PASS);
        m.put("cp", 0);
        m.put("review", review);
        return m;
    }

    /** Owner 06/11 slider: 1 or 2 situations a day, changed after the survey; today's plan keeps what it holds. */
    @Transactional
    public Map<String, Object> setDaily(Long userId, Map<String, Object> body) {
        Map<String, Object> path = requirePath(userId);
        int daily = body != null && num(body.get("dailySituations")) == 2 ? 2 : 1;
        db.update("UPDATE cpath_user_path SET daily_situations = ? WHERE id = ?", daily, path.get("id"));
        return Map.of("dailySituations", daily);
    }

    private boolean weeklyPassed(Long userId, LocalDate monday) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM pending_credit_award WHERE user_id = ? AND reference_type = ? AND reference_id = ?",
                Integer.class, userId, REF_WEEK, monday.toEpochDay());
        return n != null && n > 0;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> weekly(Long userId) {
        Map<String, Object> path = requirePath(userId);
        LocalDate today = clock.today(userId);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String testDay = weeklyTestDay(userId, path);
        boolean open = testDay == null || !today.isBefore(LocalDate.parse(testDay));
        List<WeeklyQ> qs = open ? weeklyQuestions(userId, path, monday) : List.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weekStart", monday.toString());
        m.put("testDay", testDay);
        m.put("open", open);
        m.put("passScore", WEEKLY_PASS);
        m.put("passed", weeklyPassed(userId, monday));
        m.put("questions", qs.stream().map(q -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", q.id());
            x.put("ja", q.ja());
            x.put("reading", q.reading());
            x.put("audioUrl", q.audio());
            x.put("options", q.options());
            return x;
        }).toList());
        return m;
    }

    @Transactional
    public Map<String, Object> submitWeekly(Long userId, Map<String, Object> body) {
        Map<String, Object> path = requirePath(userId);
        LocalDate today = clock.today(userId);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<WeeklyQ> qs = weeklyQuestions(userId, path, monday);
        if (qs.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tuần này chưa có nội dung để kiểm tra.");
        Map<String, Object> answers = body != null && body.get("answers") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        int correct = 0;
        List<Map<String, Object>> review = new ArrayList<>();
        for (WeeklyQ q : qs) {
            Object given = answers.get(q.id());
            boolean ok = given != null && num(given) == q.answer();
            if (ok) correct++;
            // Sổ tay: a miss goes in (or counts again), a right answer takes it out
            Long pid = ((Number) path.get("id")).longValue();
            if (ok) {
                db.update("DELETE FROM cpath_user_note WHERE path_id = ? AND item_code = ?", pid, q.id());
            } else {
                db.update("INSERT INTO cpath_user_note (path_id, item_code, kind) VALUES (?,?,?) "
                        + "ON DUPLICATE KEY UPDATE wrong_count = wrong_count + 1", pid, q.id(), q.id().matches(".*-L\\d+$") ? "LINE" : "ITEM");
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", q.id());
            r.put("ja", q.ja());
            r.put("correct", ok);
            r.put("answer", q.options().get(q.answer()));
            review.add(r);
        }
        int score = Math.round(100f * correct / qs.size());
        boolean pass = score >= WEEKLY_PASS;
        int cp = 0;
        if (pass && !weeklyPassed(userId, monday)) {
            cp = award(userId, WEEK_CP, REF_WEEK, monday.toEpochDay(), "Đạt kiểm tra tuần giao tiếp " + monday);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", score);
        m.put("correct", correct);
        m.put("total", qs.size());
        m.put("passed", pass);
        m.put("cp", cp);
        m.put("review", review);
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
                ? dateTimeOf(last.get("taken_at")).plusHours(24).toString() : null);
        boolean passed = last != null && Boolean.TRUE.equals(last.get("passed"));
        m.put("certificateUrl", passed ? "/learning/comm/certificate" : null);
        // not passed yet: the topics to work on (not passed, weakest speaking first), at most 3
        m.put("weakTopics", passed ? List.of() : db.queryForList("SELECT t.name_vi FROM cpath_topic t LEFT JOIN cpath_user_topic u "
                + "ON u.topic_code = t.code AND u.path_id = ? WHERE t.goal_code = ? AND u.passed_at IS NULL "
                + "ORDER BY COALESCE(u.speak_best, -1), t.sort_order LIMIT 3", String.class, p.get("id"), p.get("goal_code")));
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
            // the path stays open (map, review, certificate); the pass gives the goal's big reward once
            if (score >= GOAL_TEST_PASS) {
                award(userId, GOAL_CP, REF_GOAL, pathId, "Đạt bài test giao tiếp với AI");
            }
        }
        return goalTest(userId);
    }

    /** Sổ tay of the main path: missed words, phrases and learner lines, most missed first. */
    @Transactional(readOnly = true)
    public Map<String, Object> notebook(Long userId) {
        Map<String, Object> p = requirePath(userId);
        Long pathId = ((Number) p.get("id")).longValue();
        List<Map<String, Object>> rows = new ArrayList<>(db.queryForList("SELECT n.item_code id, n.kind, n.wrong_count, i.ja, i.reading, i.vi, i.audio_path, "
                + "COALESCE(i.situation_code, NULL) situation FROM cpath_user_note n JOIN cpath_item i ON i.code = n.item_code "
                + "WHERE n.path_id = ? AND n.kind = 'ITEM'", pathId));
        rows.addAll(db.queryForList("SELECT n.item_code id, n.kind, n.wrong_count, l.ja, NULL reading, l.vi, l.audio_path, l.situation_code situation "
                + "FROM cpath_user_note n JOIN cpath_line l ON CONCAT(l.situation_code, '-L', l.line_order) = n.item_code "
                + "WHERE n.path_id = ? AND n.kind = 'LINE'", pathId));
        rows.sort((a, b) -> num(b.get("wrong_count")) - num(a.get("wrong_count")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("kind", r.get("kind"));
            m.put("ja", r.get("ja"));
            m.put("reading", r.get("reading"));
            m.put("vi", r.get("vi"));
            m.put("audioUrl", url((String) r.get("audio_path")));
            m.put("situation", r.get("situation"));
            m.put("wrongCount", num(r.get("wrong_count")));
            out.add(m);
        }
        return Map.of("items", out, "total", out.size());
    }

    static final int GOAL_CP = 20;
    static final String REF_GOAL = "CPATH_GOAL";

    /** The newest passed goal test of the learner (any of their paths): goal name and when, for the certificate. */
    @Transactional(readOnly = true)
    public java.util.Optional<Map<String, Object>> certificate(Long userId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT g.name_vi, t.taken_at FROM cpath_user_goal_test t "
                + "JOIN cpath_user_path p ON p.id = t.path_id JOIN cpath_goal g ON g.code = p.goal_code "
                + "WHERE p.user_id = ? AND t.passed = 1 ORDER BY t.taken_at DESC LIMIT 1", userId);
        if (rows.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(Map.of("nameVi", String.valueOf(rows.get(0).get("name_vi")),
                "takenAt", dateTimeOf(rows.get(0).get("taken_at"))));
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
