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
    /** F68: how a goal's situation page runs its four steps (TRAVEL = listen and speak only). */
    static final String FLOW_FULL = "FULL", FLOW_LISTEN_SPEAK = "LISTEN_SPEAK";
    /** F68: Du lịch weekly test always asks this many spoken lines (weak lines first, then this week's lines). */
    static final int TRAVEL_WEEKLY_SPEAK = 2;
    static final int ROLEPLAY_PASS = 80;
    static final int SPEAK_PASS = 70;
    static final int GOAL_TEST_PASS = 70;
    static final String LEARNER = "リン";
    static final String AUDIO_URL = "/uploads/content-audio/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate db;
    private final com.jlas.jlptpath.service.JpathClock clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private CpathTeamNotebookMirror teamNotebook; // F126

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

    private org.springframework.beans.factory.ObjectProvider<com.jlas.mission.service.DailyGoalService> dailyGoals;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDailyGoals(org.springframework.beans.factory.ObjectProvider<com.jlas.mission.service.DailyGoalService> dailyGoals) {
        this.dailyGoals = dailyGoals;
    }

    /** [JLAS-W0108] F128: re-checks the learner's own daily goal after commit (situation study counts toward it). */
    private void refreshDailyGoalAfterCommit(Long userId) {
        com.jlas.mission.service.DailyGoalService goal = dailyGoals == null ? null : dailyGoals.getIfAvailable();
        if (goal == null || !org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return;
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            if (goal.refreshToday(userId)) {
                                com.jlas.mission.service.CreditAwardService credit = awards == null ? null : awards.getIfAvailable();
                                if (credit != null) credit.applyOpenAwards(userId);
                            }
                        } catch (RuntimeException ignored) {
                            // the goals page re-checks today and yesterday
                        }
                    }
                });
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
        // F172: the ledger drops a repeat of the same reference (INSERT IGNORE), so a repeat earns nothing — the
        // screen said "+4 CP" for an extra situation while the wallet got +2 (the day bonus was counted again)
        Integer queued = db.queryForObject("SELECT COUNT(*) FROM pending_credit_award WHERE user_id = ? AND reason = ? "
                + "AND reference_type = ? AND reference_id = ?", Integer.class, userId,
                com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason.STUDY_REWARD.name(), refType, refId);
        if (queued != null && queued > 0) return 0;
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
        Map<String, List<Map<String, String>>> topicsByGoal = new LinkedHashMap<>();
        db.query("SELECT goal_code, code, name_vi FROM cpath_topic ORDER BY goal_code, sort_order", rs -> {
            topicsByGoal.computeIfAbsent(rs.getString("goal_code"), key -> new ArrayList<>())
                    .add(Map.of("code", rs.getString("code"), "nameVi", rs.getString("name_vi")));
        });
        return db.query("SELECT g.code, g.name_vi, g.fields_json, (SELECT COUNT(*) FROM cpath_topic t WHERE t.goal_code = g.code) n "
                + "FROM cpath_goal g ORDER BY g.sort_order", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goal", rs.getString("code"));
            m.put("nameVi", rs.getString("name_vi"));
            m.put("topicCount", rs.getInt("n"));
            m.put("fields", parse(rs.getString("fields_json"), List.of()));
            m.put("topics", topicsByGoal.getOrDefault(rs.getString("code"), List.of()));
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
        String requestedRole = str(req.get("role"));
        boolean secondary = "SECONDARY".equals(requestedRole) && main != null;
        if (secondary && goal.equals(db.queryForObject("SELECT goal_code FROM cpath_user_path WHERE id = ?", String.class, main))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mục tiêu phụ phải khác mục tiêu chính.");
        }
        List<Long> resumable = secondary ? List.of() : db.queryForList("SELECT id FROM cpath_user_path WHERE user_id = ? AND role = 'MAIN' AND goal_code = ? AND status IN ('ACTIVE','PAUSED') ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END, id DESC LIMIT 1", Long.class, userId, goal);
        // F118 (owner 14/11): the new main goal is the learner's secondary goal → that row becomes the main path, so
        // its situations, topics and notebook carry over instead of a fresh 0% main path
        boolean promote = false;
        if (resumable.isEmpty() && !secondary) {
            resumable = db.queryForList("SELECT id FROM cpath_user_path WHERE user_id = ? AND role = 'SECONDARY' AND goal_code = ? AND status IN ('ACTIVE','PAUSED') ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END, id DESC LIMIT 1", Long.class, userId, goal);
            promote = !resumable.isEmpty();
        }
        if (!resumable.isEmpty()) {
            Long resumeId = resumable.get(0);
            db.update("UPDATE cpath_user_path SET status = 'PAUSED' WHERE user_id = ? AND role = 'MAIN' AND status = 'ACTIVE' AND id <> ?", userId, resumeId);
            db.update("UPDATE cpath_user_path SET status = 'PAUSED' WHERE user_id = ? AND role = 'SECONDARY' AND status = 'ACTIVE' AND goal_code = ? AND id <> ?", userId, goal, resumeId);
            if (promote) db.update("UPDATE cpath_user_path SET role = 'MAIN' WHERE id = ?", resumeId);
            db.update("UPDATE cpath_user_path SET status = 'ACTIVE', self_level = ?, priority_topics_json = COALESCE(?, priority_topics_json), daily_situations = COALESCE(?, daily_situations), rest_days = COALESCE(?, rest_days), finish_date = COALESCE(?, finish_date) WHERE id = ?", level, req.containsKey("priorityTopics") ? json(req.get("priorityTopics")) : null, req.containsKey("dailySituations") ? daily : null, req.containsKey("restDays") ? String.join(",", rest.stream().map(String::valueOf).toList()) : null, finish == null || finish.toString().isBlank() ? null : finish.toString(), resumeId);
            applyKanaChoice(resumeId, req);
            return Map.of("pathId", resumeId, "role", "MAIN");
        }
        String role = secondary ? "SECONDARY" : "MAIN";
        if ("MAIN".equals(role)) db.update("UPDATE cpath_user_path SET status = 'PAUSED' WHERE user_id = ? AND role = 'MAIN' AND status = 'ACTIVE'", userId);
        if (secondary) {
            // a second goal replaces the earlier secondary one (owner 06/11: one main + one secondary)
            db.update("UPDATE cpath_user_path SET status = 'PAUSED' WHERE user_id = ? AND role = 'SECONDARY' AND status = 'ACTIVE'", userId);
        } else {
            // F113 (13/11): changing the MAIN goal no longer pauses a secondary of another goal without a word;
            // only a secondary of the new main goal steps aside (a goal is never main and secondary at once)
            db.update("UPDATE cpath_user_path SET status = 'PAUSED' WHERE user_id = ? AND role = 'SECONDARY' AND status = 'ACTIVE' AND goal_code = ?", userId, goal);
        }
        db.update("INSERT INTO cpath_user_path (user_id, goal_code, role, status, self_level, fields_json, priority_topics_json, "
                + "daily_situations, rest_days, finish_date) VALUES (?,?,?,?,?,?,?,?,?,?)", userId, goal, role, "ACTIVE", level,
                json(req.get("fields")), json(req.get("priorityTopics")), daily,
                String.join(",", rest.stream().map(String::valueOf).toList()), finish == null || finish.toString().isBlank() ? null : finish.toString());
        Long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        applyKanaChoice(id, req);
        return Map.of("pathId", id, "role", role);
    }

    /**
     * F70 (owner 12/11): "Chưa biết chữ cái" + skipping the Kana suggestion turns on full romaji for the whole path
     * (saved like the F61 display choice, so Settings can turn it off). Knowing Kana changes nothing.
     */
    private void applyKanaChoice(Long pathId, Map<String, Object> req) {
        if (!Boolean.FALSE.equals(req.get("knowsKana"))) return;
        try {
            db.update("UPDATE cpath_user_path SET display_json = ? WHERE id = ?", json(Map.of("fullRomaji", true, "romaji", true)), pathId);
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // a DB without the F61 column: the level default (romaji for Mới bắt đầu) still applies
        }
    }

    /** The active path (main or secondary) whose goal holds the situation; the main path otherwise. */
    Long pathIdForSituation(Long userId, String code) {
        Long own = ownPathIdForSituation(userId, code);
        return own == null ? mainPathId(userId) : own;
    }

    /** F57: the active path whose goal holds the situation, or null (another goal's or an unknown situation). */
    Long ownPathIdForSituation(Long userId, String code) {
        List<Long> ids = db.queryForList("SELECT p.id FROM cpath_user_path p JOIN cpath_topic t ON t.goal_code = p.goal_code "
                + "JOIN cpath_situation s ON s.topic_code = t.code WHERE p.user_id = ? AND p.status = 'ACTIVE' AND s.code = ? "
                + "ORDER BY p.role = 'MAIN' DESC, p.id DESC LIMIT 1", Long.class, userId, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    Long pathIdForTopic(Long userId, String code) {
        List<Long> ids = db.queryForList("SELECT p.id FROM cpath_user_path p JOIN cpath_topic t ON t.goal_code = p.goal_code "
                + "WHERE p.user_id = ? AND p.status = 'ACTIVE' AND t.code = ? "
                + "ORDER BY p.role = 'MAIN' DESC, p.id DESC LIMIT 1", Long.class, userId, code);
        return ids.isEmpty() ? null : ids.get(0);
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

    /**
     * Topics the learner's path covers: for WORK only the chosen fields (office always in); {@code null} = every
     * topic of the goal. F204: the tests and their wrong answers use the same set as the situation list.
     */
    @SuppressWarnings("unchecked")
    Set<String> allowedTopics(Map<String, Object> path) {
        String goal = (String) path.get("goal_code");
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
        return allowed;
    }

    /** F204: {@code " AND t.code IN (?,…)"} for the path's topics (adds the codes to {@code args}), or "". */
    String topicFilter(Map<String, Object> path, List<Object> args) {
        Set<String> allowed = allowedTopics(path);
        if (allowed == null) return "";
        args.addAll(allowed);
        return " AND t.code IN (" + String.join(",", java.util.Collections.nCopies(allowed.size(), "?")) + ")";
    }

    private static List<Object> goalArgs(Map<String, Object> path) {
        List<Object> args = new ArrayList<>();
        args.add(path.get("goal_code"));
        return args;
    }

    /** The path's situations in study order: priority topics first, then by level and topic order; WORK keeps its fields. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> orderedSituations(Map<String, Object> path) {
        String goal = (String) path.get("goal_code");
        List<Object> priority = parse((String) path.get("priority_topics_json"), List.of());
        Set<String> allowed = allowedTopics(path);
        List<Map<String, Object>> rows = db.queryForList("SELECT s.code, s.title_ja, s.title_vi, s.level, s.topic_code, t.name_vi topic_name, "
                + "t.sort_order t_order, s.sort_order s_order FROM cpath_situation s JOIN cpath_topic t ON t.code = s.topic_code "
                + "WHERE t.goal_code = ? ORDER BY t.sort_order, s.sort_order", goal);
        Set<String> keep = allowed;
        List<Map<String, Object>> out = new ArrayList<>(rows.stream().filter(r -> keep == null || keep.contains(r.get("topic_code"))).toList());
        if (CpathCoreService.hasCore(goal)) {
            // F70 (owner 12/11): depth also applies to situations; rank n of its topic is learnt from depth n on.
            // A situation already started stays (a lower depth hides only what is not learned yet).
            int depth = CpathCoreService.depthRank(CpathCoreService.depthCode(path));
            if (depth < 3) {
                Map<String, Integer> ranks = CpathCoreService.situationRanks(out);
                Set<String> started = path.get("id") == null ? Set.of() : new java.util.HashSet<>(db.queryForList(
                        // F84: only a situation with a step learned counts as started, not one merely put on a day
                        "SELECT situation_code FROM cpath_user_situation WHERE path_id = ? "
                                + "AND (done_at IS NOT NULL OR COALESCE(steps_done, '') <> '')", String.class, ((Number) path.get("id")).longValue()));
                out.removeIf(r -> ranks.getOrDefault(String.valueOf(r.get("code")), 1) > depth && !started.contains(r.get("code")));
            }
        }
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
        // F125: a finished path (final AI test passed) plans no new day; the map stays open for review
        if (planned.isEmpty() && !isRest(path, day) && !isFinished(pathId)) {
            int want = ((Number) path.get("daily_situations")).intValue();
            // F74 (12/11): a situation planned on an earlier day and not finished comes back first (a missed day carries
            // over), so the map order and "Hôm nay" stay the same instead of jumping to the next topic
            for (Map<String, Object> s : order) {
                if (planned.size() >= want) break;
                Map<String, Object> p = prog.get(s.get("code"));
                LocalDate planDay = p == null ? null : toDate(p.get("study_day"));
                if (p != null && !done(p) && planDay != null && planDay.isBefore(day)) {
                    db.update("UPDATE cpath_user_situation SET study_day = ? WHERE path_id = ? AND situation_code = ?", day, pathId, s.get("code"));
                    planned.add((String) s.get("code"));
                }
            }
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
            m.put("topicName", s.get("topic_name"));
            m.put("status", done(prog.get(s.get("code"))) ? "DONE" : "TODAY");
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> learnExtra(Long userId) {
        Map<String, Object> path = requirePath(userId);
        Long pathId = ((Number) path.get("id")).longValue();
        requireAheadAllowed(userId, path);
        Map<String, Map<String, Object>> prog = progressOf(pathId);
        for (Map<String, Object> s : orderedSituations(path)) {
            if (!prog.containsKey(s.get("code"))) {
                db.update("INSERT INTO cpath_user_situation (path_id, situation_code, study_day) VALUES (?,?,?)", pathId, s.get("code"), clock.today(userId));
                return Map.of("id", s.get("code"));
            }
        }
        return Map.of();
    }

    /** Owner 11/11 (F63): the gates for starting a situation that is not planned yet on the main path. */
    private void requireAheadAllowed(Long userId, Map<String, Object> path) {
        // a learner who already did this week's share must pass the weekly test to study ahead
        LocalDate monday = clock.today(userId).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int started = startedSince(path, monday);
        int share = perWeek(path, monday);
        if (started >= share && !weeklyPassed(userId, monday)) {
            throw new ApiException(ErrorCode.CPATH_WEEK_TEST_REQUIRED);
        }
        // at most one week ahead per calendar week, so the next weekly test still covers what was learned ahead
        if (started >= 2 * share) {
            throw new ApiException(ErrorCode.CPATH_AHEAD_LIMIT);
        }
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
        m.put("restDays", p.get("rest_days") == null || String.valueOf(p.get("rest_days")).isBlank()
                ? List.of() : List.of(String.valueOf(p.get("rest_days")).split(",")));
        m.put("finishDate", p.get("finish_date") == null ? null : p.get("finish_date").toString());
        m.put("display", displayOf(p));
        m.put("progress", Map.of("doneSituations", doneCount, "totalSituations", order.size(),
                "passedTopics", passed == null ? 0 : passed, "totalTopics", topics.size()));
        m.put("week", week(userId, p, prog));
        // the learner day of the app clock (never the browser's), and whether it is a rest day
        LocalDate learnerToday = clock.today(userId);
        Map<String, Object> todayMap = new LinkedHashMap<>();
        todayMap.put("date", learnerToday.toString());
        todayMap.put("restDay", isRest(p, learnerToday));
        todayMap.put("situations", today(userId, p));
        String testDay = weeklyTestDay(userId, p);
        todayMap.put("weeklyTestDay", testDay);
        todayMap.put("weeklyOpen", weeklyOpen(userId, p, learnerToday, testDay)); // F44: the path page shows the same rule
        // owner 17/11: the path page offers "Ôn tập hôm nay" only when something is due (Sổ tay or 1/3/7/14/30-day items)
        todayMap.put("reviewDue", reviewQuestions(userId, p).size());
        m.put("today", todayMap);
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
            // F125: a secondary takes no final test while it is secondary; "all learned" invites the promotion
            sm.put("allDone", !secOrder.isEmpty() && next == null);
            sm.put("finished", isFinished(((Number) sec.get("id")).longValue()));
            m.put("secondary", sm);
        }
        // F125 (owner 14/11): the final AI test passed → summary card, "Đã hoàn thành" label, no new daily plan
        m.put("finished", finishedSummary(userId, p, order, prog, passed == null ? 0 : passed, topics.size()));
        m.put("finishedPaths", otherFinishedPaths(userId, pathId));
        return m;
    }

    // ----------------------------------------------------------------------------------------- F125 finished

    /** The first passed final test of a path, or null (also null on a DB without the goal test table). */
    private Map<String, Object> passedGoalTest(Long pathId) {
        try {
            List<Map<String, Object>> r = db.queryForList("SELECT score, taken_at FROM cpath_user_goal_test WHERE path_id = ? "
                    + "AND passed = TRUE ORDER BY taken_at, id LIMIT 1", pathId);
            return r.isEmpty() ? null : r.get(0);
        } catch (org.springframework.dao.DataAccessException e) {
            return null;
        }
    }

    /** F125: a path is finished once its final AI test is passed (derived, no status column change). */
    boolean isFinished(Long pathId) {
        return passedGoalTest(pathId) != null;
    }

    static String certificateUrl(Object goal) {
        return "/learning/comm/certificate?goal=" + goal;
    }

    /**
     * F125: the summary of a finished path: final test score, situations / topics, start and finish day, CP the path
     * earned (from the award ledger: its situations, topics, study days and weeks, and the goal), certificate link.
     */
    private Map<String, Object> finishedSummary(Long userId, Map<String, Object> p, List<Map<String, Object>> order,
                                                Map<String, Map<String, Object>> prog, int passedTopics, int totalTopics) {
        Long pathId = ((Number) p.get("id")).longValue();
        Map<String, Object> test = passedGoalTest(pathId);
        if (test == null) return null;
        List<Map<String, Object>> doneRows = prog.values().stream().filter(CpathService::done).toList();
        Set<String> topicCodes = new LinkedHashSet<>();
        order.forEach(s -> topicCodes.add((String) s.get("topic_code")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goal", p.get("goal_code"));
        m.put("nameVi", p.get("name_vi"));
        m.put("score", test.get("score") == null ? null : num(test.get("score")));
        LocalDate started = dateOf(p.get("created_at"));
        if (started == null) {
            started = prog.values().stream().map(r -> toDate(r.get("study_day"))).filter(java.util.Objects::nonNull)
                    .min(LocalDate::compareTo).orElse(null);
        }
        m.put("startedAt", started == null ? null : started.toString());
        LocalDate finishedAt = dateOf(test.get("taken_at"));
        m.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        m.put("doneSituations", order.stream().filter(s -> done(prog.get(s.get("code")))).count());
        m.put("totalSituations", order.size());
        m.put("passedTopics", passedTopics);
        m.put("totalTopics", totalTopics);
        m.put("cp", pathCp(userId, pathId, doneRows, topicCodes));
        m.put("certificateUrl", certificateUrl(p.get("goal_code")));
        return m;
    }

    /** CP the learner got from this path, read from the award ledger (each award is kept once there). */
    private int pathCp(Long userId, Long pathId, List<Map<String, Object>> doneRows, Set<String> topicCodes) {
        Set<Long> situations = new LinkedHashSet<>();
        Set<Long> days = new LinkedHashSet<>();
        Set<Long> weeks = new LinkedHashSet<>();
        for (Map<String, Object> r : doneRows) {
            situations.add(situationRef(String.valueOf(r.get("situation_code"))));
            LocalDate d = toDate(r.get("study_day"));
            if (d != null) {
                days.add(d.toEpochDay());
                weeks.add(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay());
            }
        }
        Set<Long> topics = new LinkedHashSet<>();
        for (String t : topicCodes) topics.add(situationRef(t));
        StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(amount), 0) FROM pending_credit_award WHERE user_id = ? AND (")
                .append("(reference_type = ? AND reference_id = ?)");
        List<Object> args = new ArrayList<>(List.of(userId, REF_GOAL, pathId));
        appendRefs(sql, args, REF_SITUATION, situations);
        appendRefs(sql, args, REF_TOPIC, topics);
        appendRefs(sql, args, REF_DAY, days);
        appendRefs(sql, args, REF_WEEK, weeks);
        sql.append(')');
        try {
            Integer n = db.queryForObject(sql.toString(), Integer.class, args.toArray());
            return n == null ? 0 : n;
        } catch (org.springframework.dao.DataAccessException e) {
            return 0;
        }
    }

    private static void appendRefs(StringBuilder sql, List<Object> args, String type, Set<Long> ids) {
        if (ids.isEmpty()) return;
        sql.append(" OR (reference_type = ? AND reference_id IN (").append(String.join(",", java.util.Collections.nCopies(ids.size(), "?"))).append("))");
        args.add(type);
        args.addAll(ids);
    }

    /** F125: the learner's other finished paths (paused after a promotion or a goal change), newest first. */
    private List<Map<String, Object>> otherFinishedPaths(Long userId, Long exceptId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> rows;
        try {
            rows = db.queryForList("SELECT p.id, p.goal_code, g.name_vi FROM cpath_user_path p JOIN cpath_goal g ON g.code = p.goal_code "
                    + "WHERE p.user_id = ? AND p.id <> ? AND EXISTS (SELECT 1 FROM cpath_user_goal_test t WHERE t.path_id = p.id AND t.passed = TRUE) "
                    + "ORDER BY p.id DESC", userId, exceptId);
        } catch (org.springframework.dao.DataAccessException e) {
            return out;
        }
        Set<Object> seen = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            if (!seen.add(r.get("goal_code"))) continue;
            Map<String, Object> test = passedGoalTest(((Number) r.get("id")).longValue());
            LocalDate at = test == null ? null : dateOf(test.get("taken_at"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goal", r.get("goal_code"));
            m.put("nameVi", r.get("name_vi"));
            m.put("score", test == null || test.get("score") == null ? null : num(test.get("score")));
            m.put("finishedAt", at == null ? null : at.toString());
            m.put("certificateUrl", certificateUrl(r.get("goal_code")));
            out.add(m);
        }
        return out;
    }

    /**
     * F125 "Nâng mục tiêu phụ lên làm chính": the F118 switch (MAIN requested for the secondary's goal promotes that
     * row) with the secondary's own level, so its situations, topics, notebook and level carry over.
     */
    @Transactional
    public Map<String, Object> promoteSecondary(Long userId) {
        Map<String, Object> sec = pathRow(userId, "SECONDARY");
        if (sec == null) throw new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có mục tiêu phụ để nâng lên làm chính.");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("goal", sec.get("goal_code"));
        req.put("role", "MAIN");
        req.put("selfLevel", sec.get("self_level"));
        return createPath(userId, req);
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
                node.put("quizBest", t == null ? null : t.get("roleplay_test_best"));
                node.put("missingRoleplay", t == null || t.get("roleplay_test_best") == null || num(t.get("roleplay_test_best")) < ROLEPLAY_PASS);
                node.put("missingSpeak", t == null || t.get("speak_best") == null || num(t.get("speak_best")) < SPEAK_PASS);
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
        // F125: a finished path opens on its last week (the passed AI test) and locks no week (review only)
        boolean finished = goalTest != null && (num(goalTest.get("passed")) != 0 || Boolean.TRUE.equals(goalTest.get("passed")));
        if (current < 0) current = finished ? Math.max(0, weeks.size() - 1) : 0;
        for (int w = finished ? weeks.size() : current + 1; w < weeks.size(); w++) {
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weeks", weeks);
        out.put("currentWeek", current);
        out.put("finished", finished);
        return out;
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
        // F68 (owner 11/11): Du lịch only listens and speaks — step 1 (WORDS) becomes "Nghe câu → chọn nghĩa",
        // the words and grammar sit behind "Chú thích", the AI call is optional; the step keys stay the same
        m.put("flow", "TRAVEL".equals(s.get("goal_code")) ? FLOW_LISTEN_SPEAK : FLOW_FULL);
        // F61/F156: the situation page opens with the display saved in Cài đặt; secondary goals inherit main custom display
        Long displayPath = pathIdForSituation(userId, code);
        List<Map<String, Object>> pathRows = displayPath == null ? List.of()
                : db.queryForList("SELECT * FROM cpath_user_path WHERE id = ?", displayPath);
        Map<String, Object> disp = displayOf(pathRows.isEmpty() ? null : pathRows.get(0));
        if (!Boolean.TRUE.equals(disp.get("custom"))) {
            Long mainId = mainPathId(userId);
            if (mainId != null && !mainId.equals(displayPath)) {
                List<Map<String, Object>> mainRows = db.queryForList("SELECT * FROM cpath_user_path WHERE id = ?", mainId);
                if (!mainRows.isEmpty()) {
                    Map<String, Object> mainDisp = displayOf(mainRows.get(0));
                    if (Boolean.TRUE.equals(mainDisp.get("custom"))) {
                        Map<String, Object> merged = new LinkedHashMap<>(disp);
                        for (String k : DISPLAY_KEYS) merged.put(k, mainDisp.get(k));
                        merged.put("speed", mainDisp.get("speed"));
                        merged.put("custom", true);
                        merged.put("fullRomaji", Boolean.TRUE.equals(disp.get("fullRomaji")) && Boolean.TRUE.equals(merged.get("romaji")));
                        disp = merged;
                    }
                }
            }
        }
        m.put("display", disp);
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
        m.put("vocab", m.get("words"));
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
        Long situationPath = pathIdForSituation(userId, code);
        if (situationPath != null)
            m.put("selfLevel", db.queryForObject("SELECT self_level FROM cpath_user_path WHERE id = ?", String.class, situationPath));
        Map<String, Object> prog = situationPath == null ? null : progressOf(situationPath).get(code);
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
            Map<String, Object> rightOpt = new LinkedHashMap<>();
            rightOpt.put("ja", right.get("ja"));
            rightOpt.put("vi", right.get("vi") == null ? "" : right.get("vi"));
            rightOpt.put("furigana", right.get("furigana") == null ? "" : right.get("furigana"));
            options.add(rightOpt);
            for (Object d : (List<Object>) t.getOrDefault("distractors", List.of())) {
                Map<String, Object> dm = (Map<String, Object>) d;
                Map<String, Object> distOpt = new LinkedHashMap<>();
                distOpt.put("ja", String.valueOf(dm.get("ja")));
                distOpt.put("vi", String.valueOf(dm.getOrDefault("vi", "")));
                if (dm.get("furigana") != null) distOpt.put("furigana", String.valueOf(dm.get("furigana")));
                if (dm.get("reading") != null) distOpt.put("reading", String.valueOf(dm.get("reading")));
                options.add(distOpt);
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
        if ("SPEAK".equals(st)) {
            Integer lines = db.queryForObject("SELECT COUNT(*) FROM cpath_line WHERE situation_code = ? AND learner = 1", Integer.class, code);
            if (lines == null || lines == 0)
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Tình huống chưa có câu luyện nói. Hãy quay về lộ trình và báo nhóm nội dung bổ sung câu thoại.");
        }
        // F57 (12/11): only a situation of a goal being learned counts — no progress or CP from another goal's
        // situation (or an unknown code) posted straight to the API
        Long pathId = ownPathIdForSituation(userId, code);
        if (pathId == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, mainPathId(userId) == null ? "Bạn chưa có lộ trình giao tiếp."
                    : "Tình huống này không thuộc lộ trình Giao tiếp bạn đang học.");
        }
        if (!progressOf(pathId).containsKey(code)) {
            // F57 (12/11): a situation not planned yet (a locked week opened by its URL) starts only through the same
            // gates as "Học trước" on the main path; the secondary goal stays free study
            Map<String, Object> owner = db.queryForMap("SELECT * FROM cpath_user_path WHERE id = ?", pathId);
            if ("MAIN".equals(owner.get("role"))) requireAheadAllowed(userId, owner);
        }
        // F112 (13/11): two tabs posting steps of one situation at once — INSERT IGNORE on an existing row takes a shared
        // lock in both requests, then both UPDATE it → MySQL deadlock (one request fails) and the plain read of
        // steps_done lets one step overwrite the other. The no-op upsert takes the row's exclusive lock and the locking
        // read sees the other request's committed steps, so the two requests run one after the other.
        db.update("INSERT INTO cpath_user_situation (path_id, situation_code, study_day) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE path_id = path_id", pathId, code, clock.today(userId));
        Map<String, Object> row = db.queryForMap("SELECT * FROM cpath_user_situation WHERE path_id = ? AND situation_code = ? FOR UPDATE",
                pathId, code);
        Set<String> doneSteps = new LinkedHashSet<>();
        for (String x : String.valueOf(row.get("steps_done")).split(",")) if (!x.isBlank()) doneSteps.add(x);
        doneSteps.add(st);
        db.update("UPDATE cpath_user_situation SET steps_done = ? WHERE path_id = ? AND situation_code = ?",
                String.join(",", STEPS.stream().filter(doneSteps::contains).toList()), pathId, code);
        if ("ROLEPLAY".equals(st) && body != null) {
            // F188 (probe P3-1): the client reports the role-play count; keep it in 0 <= correct <= total <= 50
            long rpTotal = Math.max(0, Math.min(50, num(body.get("total"))));
            long rpCorrect = Math.max(0, Math.min(rpTotal, num(body.get("correct"))));
            db.update("UPDATE cpath_user_situation SET roleplay_correct = ?, roleplay_total = ? WHERE path_id = ? AND situation_code = ?",
                    rpCorrect, rpTotal, pathId, code);
        }
        if ("SPEAK".equals(st)) {
            Integer verified = verifiedSituationSpeak(pathId, code);
            if (verified != null) {
                db.update("UPDATE cpath_user_situation SET speak_best = GREATEST(COALESCE(speak_best, 0), ?) WHERE path_id = ? AND situation_code = ?",
                        verified, pathId, code);
            }
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
            if (passedTopic != null)
                cp += award(userId, TOPIC_CP, REF_TOPIC, situationRef(passedTopic), "Đạt chủ đề giao tiếp " + passedTopic);
            if (first) {
                refreshDailyGoalAfterCommit(userId); // F128
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
        out.put("speakBest", "SPEAK".equals(st) ? verifiedSituationSpeak(pathId, code) : null);
        out.put("cp", cp);
        return out;
    }

    private Integer verifiedSituationSpeak(Long pathId, String situation) {
        Map<String, Object> row = db.queryForMap("SELECT COUNT(*) total, COUNT(w.line_id) scored, AVG(w.score) mean_score "
                + "FROM cpath_line l LEFT JOIN cpath_user_weak_speech w ON w.path_id = ? "
                + "AND w.line_id = CONCAT(l.situation_code, '-L', l.line_order) "
                + "WHERE l.situation_code = ? AND l.learner = 1", pathId, situation);
        if (num(row.get("scored")) == 0 || row.get("mean_score") == null) return null;
        return Math.round(((Number) row.get("mean_score")).floatValue());
    }

    /** Combines the topic role-play result with speaking scores from every completed situation. */
    private String updateTopic(Long pathId, String situation) {
        String topic = db.queryForObject("SELECT topic_code FROM cpath_situation WHERE code = ?", String.class, situation);
        return refreshTopic(pathId, topic) ? topic : null;
    }

    private boolean refreshTopic(Long pathId, String topic) {
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
        Integer speak = speakN == rows.size() && speakN > 0 ? Math.round((float) speakSum / speakN) : null;
        db.update("INSERT INTO cpath_user_topic (path_id, topic_code, quiz_best, speak_best) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE speak_best = VALUES(speak_best)", pathId, topic, quiz, speak);
        List<Map<String, Object>> saved = db.queryForList("SELECT roleplay_test_best, passed_at FROM cpath_user_topic WHERE path_id = ? AND topic_code = ?", pathId, topic);
        if (saved.isEmpty()) return false;
        Map<String, Object> state = saved.get(0);
        boolean pass = allDone && speak != null && speak >= SPEAK_PASS
                && state.get("roleplay_test_best") != null && num(state.get("roleplay_test_best")) >= ROLEPLAY_PASS;
        if (pass && state.get("passed_at") == null) {
            db.update("UPDATE cpath_user_topic SET passed_at = CURRENT_TIMESTAMP(6) WHERE path_id = ? AND topic_code = ? AND passed_at IS NULL", pathId, topic);
            return true;
        }
        return false;
    }

    @Transactional
    int recordTopicRoleplay(Long userId, String topic, int score) {
        Long pathId = pathIdForTopic(userId, topic);
        if (pathId == null) throw new ApiException(ErrorCode.NOT_FOUND, "Chủ đề không thuộc lộ trình hiện tại.");
        db.update("INSERT INTO cpath_user_topic (path_id, topic_code, roleplay_test_best) VALUES (?,?,?) "
                        + "ON DUPLICATE KEY UPDATE roleplay_test_best = GREATEST(COALESCE(roleplay_test_best, 0), VALUES(roleplay_test_best))",
                pathId, topic, score);
        return refreshTopic(pathId, topic)
                ? award(userId, TOPIC_CP, REF_TOPIC, situationRef(topic), "Đạt chủ đề giao tiếp " + topic) : 0;
    }

    boolean topicPassed(Long userId, String topic) {
        Long pathId = pathIdForTopic(userId, topic);
        if (pathId == null) return false;
        Integer count = db.queryForObject("SELECT COUNT(*) FROM cpath_user_topic WHERE path_id = ? AND topic_code = ? AND passed_at IS NOT NULL",
                Integer.class, pathId, topic);
        return count != null && count > 0;
    }

    // -------------------------------------------------------------------------------------------------- day

    @Transactional(readOnly = true)
    public Map<String, Object> day(Long userId, LocalDate requested) {
        // the app's learner day, never the server's calendar
        LocalDate day = requested == null ? clock.today(userId) : requested;
        Map<String, Object> p = requirePath(userId);
        Long pathId = ((Number) p.get("id")).longValue();
        List<Map<String, Object>> rows = db.queryForList("SELECT u.*, s.title_vi, s.title_ja, s.topic_code FROM cpath_user_situation u "
                + "JOIN cpath_situation s ON s.code = u.situation_code JOIN cpath_topic t ON t.code = s.topic_code WHERE u.path_id = ? AND u.study_day = ? ORDER BY t.sort_order, s.sort_order, u.situation_code", pathId, day);
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
        // F68: the goal tells the day page how step 1 is called (Du lịch: "Nghe & chọn nghĩa")
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day.toString());
        out.put("today", clock.today(userId).toString());
        out.put("situations", situations);
        out.put("scores", scores);
        out.put("cp", (int) finished * SITUATION_CP + (dayDone && finished > 0 ? DAY_CP : 0));
        out.put("goal", String.valueOf(p.get("goal_code")));
        out.put("restDay", isRest(p, day)); // F116: an empty rest day says "ngày nghỉ", not "chưa có lịch"
        out.put("weekStatus", weekStatus(pathId, day));
        return out;
    }

    /**
     * F176 (AI test L3): DONE / PARTIAL / EMPTY for each day of the week holding {@code day}, so the strip ticks only the
     * days whose planned situations were all finished (it ticked every past day, empty ones too).
     */
    private Map<String, String> weekStatus(Long pathId, LocalDate day) {
        LocalDate monday = day.minusDays(day.getDayOfWeek().getValue() - 1L);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) out.put(monday.plusDays(i).toString(), "EMPTY");
        for (Map<String, Object> r : db.queryForList("SELECT study_day, COUNT(*) n, SUM(CASE WHEN done_at IS NULL THEN 0 ELSE 1 END) d "
                + "FROM cpath_user_situation WHERE path_id = ? AND study_day BETWEEN ? AND ? GROUP BY study_day",
                pathId, monday, monday.plusDays(6))) {
            Object raw = r.get("study_day");
            LocalDate sd = raw instanceof LocalDate ld ? ld : raw instanceof java.sql.Date sq ? sq.toLocalDate() : dateOf(raw);
            if (sd == null) continue;
            long n = ((Number) r.get("n")).longValue();
            long d = r.get("d") == null ? 0 : ((Number) r.get("d")).longValue();
            out.put(sd.toString(), n > 0 && d >= n ? "DONE" : (d > 0 ? "PARTIAL" : "EMPTY"));
        }
        return out;
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
                + "JOIN cpath_situation s ON s.code = u.situation_code WHERE u.path_id = ? AND u.steps_done <> '' "
                + "AND (u.study_day BETWEEN ? AND ? "
                // F65 (11/11): what was learned ahead last week after its test was passed is checked this week
                + "OR (u.study_day BETWEEN ? AND ? AND COALESCE(u.done_at, CURRENT_TIMESTAMP(6)) > "
                + "(SELECT MIN(a.created_at) FROM pending_credit_award a WHERE a.user_id = ? AND a.reference_type = ? AND a.reference_id = ?)))",
                pathId, monday, today, monday.minusDays(7), monday.minusDays(1), userId, REF_WEEK, monday.minusDays(7).toEpochDay());
        if (studied.isEmpty()) return List.of();
        List<String> sits = studied.stream().map(r -> (String) r.get("situation_code")).toList();
        Set<String> topics = new LinkedHashSet<>(studied.stream().map(r -> (String) r.get("topic_code")).toList());
        String in = String.join(",", java.util.Collections.nCopies(sits.size(), "?"));
        String tin = String.join(",", java.util.Collections.nCopies(topics.size(), "?"));
        List<Object> args = new ArrayList<>(sits);
        args.addAll(topics);
        List<Map<String, Object>> pool = new ArrayList<>(db.queryForList("SELECT code id, ja, reading, vi, audio_path, kind FROM cpath_item "
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
        // F183 (AI test L13): a word is asked among words and a phrase among phrases (a word had whole sentences as
        // wrong answers, so the right one stood out)
        Map<String, List<String>> itemVi = new LinkedHashMap<>();
        for (Map<String, Object> m : pool) {
            itemVi.computeIfAbsent(String.valueOf(m.get("kind")), k -> new ArrayList<>()).add(String.valueOf(m.get("vi")));
        }
        List<String> allItemVi = new ArrayList<>();
        for (Map<String, Object> m : pool) allItemVi.add(String.valueOf(m.get("vi")));
        List<Object> lineArgs = goalArgs(path);
        List<String> lineVi = lines.isEmpty() ? List.of() : db.queryForList("SELECT l.vi FROM cpath_line l JOIN cpath_situation s ON s.code = l.situation_code "
                + "JOIN cpath_topic t ON t.code = s.topic_code WHERE l.learner = 1 AND l.vi IS NOT NULL AND t.goal_code = ?"
                + topicFilter(path, lineArgs), String.class, lineArgs.toArray());
        Set<Object> lineIds = new java.util.HashSet<>();
        for (Map<String, Object> m : lines) lineIds.add(m.get("id"));
        List<WeeklyQ> out = new ArrayList<>();
        for (Map<String, Object> m : asked) {
            String right = String.valueOf(m.get("vi"));
            List<String> opts = new ArrayList<>();
            opts.add(right);
            List<String> sameKind = itemVi.getOrDefault(String.valueOf(m.get("kind")), List.of());
            List<String> others = new ArrayList<>(new LinkedHashSet<>(lineIds.contains(m.get("id")) ? lineVi
                    : sameKind.size() >= 4 ? sameKind : allItemVi));
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

    // ----------------------------------------------------------------------------- F64 "Tôi biết tình huống này"

    static final int KNOWN_PASS = 90;

    /**
     * F64/F69 (owner 11/11): "Tôi biết tình huống này" — up to 10 meaning questions on the situation's learner lines,
     * phrases and topic words; >= 90% counts the situation as learned instead of studying it (no situation CP).
     * Same gates as starting the situation; the questions are rebuilt from a seed of user + situation + day.
     */
    private List<WeeklyQ> knownQuestions(Long userId, Map<String, Object> path, String code) {
        List<Map<String, Object>> sit = db.queryForList("SELECT topic_code FROM cpath_situation WHERE code = ?", code);
        if (sit.isEmpty()) return List.of();
        String topic = (String) sit.get(0).get("topic_code");
        java.util.Random rnd = new java.util.Random(userId * 31 + code.hashCode() * 17L + clock.today(userId).toEpochDay());
        List<Map<String, Object>> lines = new ArrayList<>(db.queryForList("SELECT CONCAT(situation_code, '-L', line_order) id, ja, NULL reading, vi, audio_path "
                + "FROM cpath_line WHERE learner = 1 AND vi IS NOT NULL AND situation_code = ?", code));
        List<Map<String, Object>> phrases = new ArrayList<>(db.queryForList("SELECT code id, ja, reading, vi, audio_path FROM cpath_item "
                + "WHERE vi IS NOT NULL AND situation_code = ?", code));
        List<Map<String, Object>> words = new ArrayList<>(db.queryForList("SELECT code id, ja, reading, vi, audio_path FROM cpath_item "
                + "WHERE vi IS NOT NULL AND kind = 'WORD' AND topic_code = ?", topic));
        java.util.Collections.shuffle(lines, rnd);
        java.util.Collections.shuffle(phrases, rnd);
        java.util.Collections.shuffle(words, rnd);
        List<Map<String, Object>> asked = new ArrayList<>(lines.subList(0, Math.min(3, lines.size())));
        for (Map<String, Object> m : phrases) { if (asked.size() >= 6) break; asked.add(m); }
        for (Map<String, Object> m : words) { if (asked.size() >= WEEKLY_MAX) break; asked.add(m); }
        // distractors of the same kind, so the answer never stands out: a sentence among sentences, a word among words
        List<String> wordVi = new ArrayList<>();
        for (Map<String, Object> m : words) wordVi.add(String.valueOf(m.get("vi")));
        List<String> phraseVi = db.queryForList("SELECT i.vi FROM cpath_item i WHERE i.vi IS NOT NULL AND i.kind = 'PHRASE' AND i.topic_code = ?",
                String.class, topic);
        Set<Object> lineIds = new java.util.HashSet<>();
        for (Map<String, Object> m : lines) lineIds.add(m.get("id"));
        Set<Object> phraseIds = new java.util.HashSet<>();
        for (Map<String, Object> m : phrases) phraseIds.add(m.get("id"));
        List<Object> lineArgs = goalArgs(path);
        List<String> lineVi = lines.isEmpty() && phrases.isEmpty() ? List.of() : db.queryForList("SELECT l.vi FROM cpath_line l JOIN cpath_situation s ON s.code = l.situation_code "
                + "JOIN cpath_topic t ON t.code = s.topic_code WHERE l.learner = 1 AND l.vi IS NOT NULL AND t.goal_code = ?"
                + topicFilter(path, lineArgs), String.class, lineArgs.toArray());
        List<WeeklyQ> out = new ArrayList<>();
        for (Map<String, Object> m : asked) {
            String right = String.valueOf(m.get("vi"));
            List<String> opts = new ArrayList<>();
            opts.add(right);
            List<String> pool = new ArrayList<>();
            if (lineIds.contains(m.get("id")) || phraseIds.contains(m.get("id"))) {
                pool.addAll(phraseVi);
                pool.addAll(lineVi);
            } else {
                pool.addAll(wordVi);
            }
            List<String> others = new ArrayList<>(new LinkedHashSet<>(pool));
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

    /** The path a known-check runs on: the learner's own goal, through the same gates as starting the situation. */
    private Map<String, Object> knownPath(Long userId, String code) {
        Long pathId = ownPathIdForSituation(userId, code);
        if (pathId == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, mainPathId(userId) == null ? "Bạn chưa có lộ trình giao tiếp."
                    : "Tình huống này không thuộc lộ trình Giao tiếp bạn đang học.");
        }
        Map<String, Object> owner = db.queryForMap("SELECT * FROM cpath_user_path WHERE id = ?", pathId);
        if (!progressOf(pathId).containsKey(code) && "MAIN".equals(owner.get("role"))) requireAheadAllowed(userId, owner);
        return owner;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> knownCheck(Long userId, String code) {
        Map<String, Object> path = knownPath(userId, code);
        Map<String, Object> pr = progressOf(((Number) path.get("id")).longValue()).get(code);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("situation", code);
        m.put("passScore", KNOWN_PASS);
        m.put("alreadyDone", done(pr));
        // F111: one try a day, so a failed try (which shows the answers) cannot be resubmitted at 100% straight away
        boolean triedToday = knownTriedToday(((Number) path.get("id")).longValue(), code, clock.today(userId));
        m.put("triedToday", triedToday);
        m.put("retryOn", triedToday ? clock.today(userId).plusDays(1).toString() : null);
        m.put("display", displayOf(path));
        m.put("questions", done(pr) || triedToday ? List.of() : knownQuestions(userId, path, code).stream().map(q -> {
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
    public Map<String, Object> submitKnownCheck(Long userId, String code, Map<String, Object> body) {
        Map<String, Object> path = knownPath(userId, code);
        Long pathId = ((Number) path.get("id")).longValue();
        if (done(progressOf(pathId).get(code))) throw new ApiException(ErrorCode.CONFLICT, "Tình huống này đã học xong.");
        List<WeeklyQ> qs = knownQuestions(userId, path, code);
        if (qs.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tình huống này chưa có câu để kiểm tra.");
        // F111: the attempt row is the lock - a second submit the same day (another tab, a double click) finds it taken
        LocalDate today = clock.today(userId);
        if (db.update("INSERT IGNORE INTO cpath_known_attempt (path_id, situation_code, attempt_day) VALUES (?,?,?)", pathId, code, today) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Hôm nay bạn đã thử bài này rồi. Ngày mai thử lại nhé, hoặc học tình huống như bình thường.");
        }
        Map<String, Object> answers = body != null && body.get("answers") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        int correct = 0;
        List<Map<String, Object>> review = new ArrayList<>();
        for (WeeklyQ q : qs) {
            Object given = answers.get(q.id());
            boolean ok = given != null && num(given) == q.answer();
            if (ok) correct++;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", q.id());
            r.put("ja", q.ja());
            r.put("correct", ok);
            r.put("answer", q.options().get(q.answer()));
            review.add(r);
        }
        int score = Math.round(100f * correct / qs.size());
        boolean pass = score >= KNOWN_PASS;
        db.update("UPDATE cpath_known_attempt SET score = ? WHERE path_id = ? AND situation_code = ? AND attempt_day = ?", score, pathId, code, today);
        if (pass) {
            // counted as learned: every step ticked, no situation CP (the CP is for studying it)
            db.update("INSERT IGNORE INTO cpath_user_situation (path_id, situation_code, study_day) VALUES (?,?,?)", pathId, code, clock.today(userId));
            db.update("UPDATE cpath_user_situation SET steps_done = ?, done_at = COALESCE(done_at, CURRENT_TIMESTAMP(6)) WHERE path_id = ? AND situation_code = ?",
                    String.join(",", STEPS), pathId, code);
            updateTopic(pathId, code);
            refreshDailyGoalAfterCommit(userId); // F128
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", score);
        m.put("correct", correct);
        m.put("total", qs.size());
        m.put("passed", pass);
        m.put("passScore", KNOWN_PASS);
        m.put("review", review);
        m.put("retryOn", pass ? null : today.plusDays(1).toString());
        return m;
    }

    private boolean knownTriedToday(Long pathId, String code, LocalDate today) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM cpath_known_attempt WHERE path_id = ? AND situation_code = ? AND attempt_day = ?",
                Integer.class, pathId, code, today);
        return n != null && n > 0;
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
        List<Object> itemArgs = goalArgs(path);
        List<String> itemVi = db.queryForList("SELECT i.vi FROM cpath_item i JOIN cpath_topic t ON t.code = i.topic_code WHERE t.goal_code = ? AND i.vi IS NOT NULL"
                + topicFilter(path, itemArgs), String.class, itemArgs.toArray());
        List<Object> lineArgs = goalArgs(path);
        List<String> lineVi = db.queryForList("SELECT l.vi FROM cpath_line l JOIN cpath_situation s ON s.code = l.situation_code "
                + "JOIN cpath_topic t ON t.code = s.topic_code WHERE l.learner = 1 AND l.vi IS NOT NULL AND t.goal_code = ?"
                + topicFilter(path, lineArgs), String.class, lineArgs.toArray());
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
        m.put("display", displayOf(path)); // F70: listening speed of the clips
        m.put("speakingQuestions", reviewSpeakingQuestions(userId));
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

    @Transactional(readOnly = true)
    List<Map<String, Object>> weeklySpeakingQuestions(Long userId) {
        return weakSpeechQuestions(userId, true);
    }

    @Transactional(readOnly = true)
    List<Map<String, Object>> reviewSpeakingQuestions(Long userId) {
        return weakSpeechQuestions(userId, false);
    }

    private List<Map<String, Object>> weakSpeechQuestions(Long userId, boolean thisWeek) {
        Map<String, Object> path = requirePath(userId);
        LocalDate monday = clock.today(userId).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String sql = "SELECT w.line_id id, l.ja, l.vi, l.audio_path FROM cpath_user_weak_speech w "
                + "JOIN cpath_line l ON CONCAT(l.situation_code, '-L', l.line_order) = w.line_id "
                + "WHERE w.path_id = ? AND w.score < ? "
                + (thisWeek ? "AND w.updated_at >= ? " : "") + "ORDER BY w.updated_at DESC LIMIT 2";
        List<Map<String, Object>> rows = new ArrayList<>(thisWeek
                ? db.queryForList(sql, path.get("id"), SPEAK_PASS, java.sql.Timestamp.valueOf(monday.atStartOfDay()))
                : db.queryForList(sql, path.get("id"), SPEAK_PASS));
        if (thisWeek && "TRAVEL".equals(path.get("goal_code")) && rows.size() < TRAVEL_WEEKLY_SPEAK) {
            // F68 (owner 11/11): Du lịch weekly test = listen and pick the meaning + 1–2 lines scored by pronunciation,
            // so with no weak line yet it asks the learner's lines of the situations studied this week
            Set<Object> have = new java.util.HashSet<>();
            for (Map<String, Object> r : rows) have.add(r.get("id"));
            List<Map<String, Object>> week = db.queryForList("SELECT CONCAT(l.situation_code, '-L', l.line_order) id, l.ja, l.vi, l.audio_path "
                    + "FROM cpath_user_situation u JOIN cpath_line l ON l.situation_code = u.situation_code AND l.learner = 1 "
                    + "WHERE u.path_id = ? AND u.steps_done <> '' AND u.study_day BETWEEN ? AND ? "
                    + "ORDER BY u.study_day DESC, l.situation_code, l.line_order", path.get("id"), monday, clock.today(userId));
            java.util.Collections.shuffle(week, new java.util.Random(userId * 29 + monday.toEpochDay()));
            for (Map<String, Object> r : week) {
                if (rows.size() >= TRAVEL_WEEKLY_SPEAK) break;
                if (have.add(r.get("id"))) rows.add(r);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", row.get("id"));
            q.put("ja", row.get("ja"));
            q.put("vi", row.get("vi"));
            q.put("audioUrl", url((String) row.get("audio_path")));
            out.add(q);
        }
        return out;
    }

    @Transactional
    void recordSpeechScore(Long userId, String lineId, String text, int score) {
        List<Map<String, Object>> lines = db.queryForList("SELECT l.ja, p.id path_id FROM cpath_line l "
                + "JOIN cpath_situation s ON s.code = l.situation_code "
                + "JOIN cpath_topic t ON t.code = s.topic_code "
                + "JOIN cpath_user_path p ON p.goal_code = t.goal_code "
                + "WHERE CONCAT(l.situation_code, '-L', l.line_order) = ? "
                + "AND l.learner = 1 AND p.user_id = ? AND p.status = 'ACTIVE' "
                + "ORDER BY p.role = 'MAIN' DESC, p.id DESC LIMIT 1", lineId, userId);
        if (lines.isEmpty() || !String.valueOf(lines.get(0).get("ja")).equals(text))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Câu nói không thuộc lộ trình hiện tại.");
        db.update("INSERT INTO cpath_user_weak_speech (path_id, line_id, score) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE score = VALUES(score), updated_at = CURRENT_TIMESTAMP(6)",
                lines.get(0).get("path_id"), lineId, score);
    }

    @Transactional
    Map<String, Object> recordWeeklySpeech(Long userId, Map<String, Integer> scores) {
        return recordSpeechPractice(userId, scores, true);
    }

    @Transactional
    Map<String, Object> recordReviewSpeech(Long userId, Map<String, Integer> scores) {
        return recordSpeechPractice(userId, scores, false);
    }

    private Map<String, Object> recordSpeechPractice(Long userId, Map<String, Integer> scores, boolean thisWeek) {
        List<Map<String, Object>> questions = thisWeek ? weeklySpeakingQuestions(userId) : reviewSpeakingQuestions(userId);
        Map<String, Object> path = requirePath(userId);
        for (Map<String, Object> q : questions) {
            String id = String.valueOf(q.get("id"));
            Integer score = scores.get(id);
            if (score == null) continue;
            recordSpeechScore(userId, id, String.valueOf(q.get("ja")), score);
            if (score >= SPEAK_PASS) {
                db.update("DELETE FROM cpath_user_note WHERE path_id = ? AND item_code = ?", path.get("id"), id);
            } else {
                db.update("INSERT INTO cpath_user_note (path_id, item_code, kind) VALUES (?,?,'LINE') "
                        + "ON DUPLICATE KEY UPDATE wrong_count = wrong_count + 1", path.get("id"), id);
            }
        }
        return Map.of("scores", scores, "updated", scores.size());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reviewPick(Long userId, String questionId, int optionIndex) {
        WeeklyQ q = reviewQuestions(userId, requirePath(userId)).stream()
                .filter(item -> item.id().equals(questionId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Câu ôn tập không hợp lệ."));
        if (optionIndex < 0 || optionIndex >= q.options().size())
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Lựa chọn không hợp lệ.");
        return Map.of("correct", optionIndex == q.answer());
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
            if (teamNotebook != null) teamNotebook.answered(userId, q.id(), ok); // F126: chép sang sổ "Lộ trình học"
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
    public Map<String, Object> setPace(Long userId, Map<String, Object> body) {
        Map<String, Object> path = requirePath(userId);
        if (body == null || !(body.get("restDays") instanceof List<?> rest))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Hãy chọn lịch học hợp lệ.");
        if (num(body.get("dailySituations")) != 1 && num(body.get("dailySituations")) != 2)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn 1 hoặc 2 tình huống mỗi ngày.");
        List<String> days = rest.stream().map(String::valueOf).map(x -> x.toUpperCase(Locale.ROOT)).toList();
        // F118: each bad list gets its own reason (an unknown day used to say "keep one study day")
        if (!Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").containsAll(days))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ngày nghỉ không hợp lệ.");
        if (days.size() != new LinkedHashSet<>(days).size())
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mỗi ngày nghỉ chỉ chọn một lần.");
        if (days.size() >= 7)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cần giữ ít nhất một ngày học trong tuần.");
        Object rawDate = body.get("finishDate");
        LocalDate finish = null;
        if (rawDate != null && !String.valueOf(rawDate).isBlank()) {
            try { finish = LocalDate.parse(String.valueOf(rawDate)); }
            catch (RuntimeException e) { throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ngày hoàn thành không hợp lệ."); }
        }
        if (finish != null && finish.isBefore(clock.today(userId)))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ngày hoàn thành phải từ hôm nay trở đi.");
        Long pathId = ((Number) path.get("id")).longValue();
        db.update("UPDATE cpath_user_path SET rest_days = ?, finish_date = ? WHERE id = ?",
                String.join(",", days), finish, pathId);
        setDaily(userId, body);
        return path(userId);
    }

    @Transactional
    public Map<String, Object> setDaily(Long userId, Map<String, Object> body) {
        Map<String, Object> path = requirePath(userId);
        int daily = body != null && num(body.get("dailySituations")) == 2 ? 2 : 1;
        Long pathId = ((Number) path.get("id")).longValue();
        db.update("UPDATE cpath_user_path SET daily_situations = ? WHERE id = ?", daily, pathId);
        // today's plan follows the new pace: fewer drops only situations not started yet, more adds the next ones
        LocalDate day = clock.today(userId);
        List<Map<String, Object>> planned = db.queryForList("SELECT u.situation_code, u.steps_done, u.done_at FROM cpath_user_situation u "
                + "JOIN cpath_situation s ON s.code = u.situation_code JOIN cpath_topic t ON t.code = s.topic_code "
                + "WHERE u.path_id = ? AND u.study_day = ? ORDER BY t.sort_order, s.sort_order, u.situation_code", pathId, day);
        int keep = planned.size();
        for (int i = planned.size() - 1; i >= 0 && keep > daily; i--) {
            Map<String, Object> r = planned.get(i);
            boolean started = r.get("done_at") != null || (r.get("steps_done") != null && !String.valueOf(r.get("steps_done")).isBlank());
            if (!started) {
                db.update("DELETE FROM cpath_user_situation WHERE path_id = ? AND situation_code = ? AND study_day = ?",
                        pathId, r.get("situation_code"), day);
                keep--;
            }
        }
        if (!planned.isEmpty() && keep < daily && !isRest(path, day)) {
            Map<String, Map<String, Object>> prog = progressOf(pathId);
            for (Map<String, Object> s : orderedSituations(path)) {
                if (keep >= daily) break;
                if (!prog.containsKey(s.get("code"))) {
                    db.update("INSERT INTO cpath_user_situation (path_id, situation_code, study_day) VALUES (?,?,?)", pathId, s.get("code"), day);
                    keep++;
                }
            }
        }
        return Map.of("dailySituations", daily);
    }

    // ------------------------------------------------------------------------------------- F61 settings

    static final List<String> DISPLAY_KEYS = List.of("furigana", "romaji", "translation");
    static final String RESTART_TEXT = "BẮT ĐẦU LẠI";

    /** Owner 12/11 (F61): the learner's saved choice wins; with none (or a DB without the column) the level decides. */
    static Map<String, Object> displayOf(Map<String, Object> path) {
        String level = path == null ? null : (String) path.get("self_level");
        // F68 (owner 11/11): a traveller sees Japanese + translation at every level; romaji only on the button
        boolean travel = path != null && "TRAVEL".equals(path.get("goal_code"));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("furigana", travel || !"INTERMEDIATE".equals(level));
        d.put("romaji", !travel && "BEGINNER".equals(level));
        d.put("translation", travel || !"INTERMEDIATE".equals(level));
        Object raw = path == null ? null : path.get("display_json");
        Map<?, ?> saved = raw == null ? Map.of() : parse(String.valueOf(raw), Map.of());
        // F70: a learner who skipped Kana sees romaji on every line and word until they turn it off
        boolean fullRomaji = Boolean.TRUE.equals(saved.get("fullRomaji"));
        if (fullRomaji) d.put("romaji", true);
        for (String k : DISPLAY_KEYS) if (saved.get(k) instanceof Boolean b) d.put(k, b);
        d.put("fullRomaji", fullRomaji && Boolean.TRUE.equals(d.get("romaji")));
        // F70/F79: listening speed of every communication audio (0.75 / 1 / 1.25 / 1.5), default the recording's
        d.put("speed", speedOf(saved.get("speed")));
        d.put("custom", DISPLAY_KEYS.stream().anyMatch(saved::containsKey));
        return d;
    }

    static final List<Double> SPEEDS = List.of(0.75, 1.0, 1.25, 1.5);

    static double speedOf(Object raw) {
        double v = raw instanceof Number n ? n.doubleValue() : 1.0;
        for (double s : SPEEDS) if (Math.abs(s - v) < 0.001) return s;
        return 1.0;
    }

    /** F61/F156: saves furigana / romaji / translation of the main path and syncs other active paths; {"reset": true} restores level defaults. */
    @Transactional
    public Map<String, Object> setDisplay(Long userId, Map<String, Object> body) {
        Map<String, Object> path = requirePath(userId);
        Long mainId = ((Number) path.get("id")).longValue();
        String value = null;
        Map<?, ?> saved = path.get("display_json") == null ? Map.of() : parse(String.valueOf(path.get("display_json")), Map.of());
        Map<String, Object> current = displayOf(path);
        boolean reset = body != null && Boolean.TRUE.equals(body.get("reset"));
        boolean onlySpeed = body != null && body.containsKey("speed") && DISPLAY_KEYS.stream().noneMatch(body::containsKey);
        Map<String, Object> next = new LinkedHashMap<>();
        if (reset) {
            // back to the level defaults; the speed is a listening choice, not a display one, so it stays
            Map<String, Object> keep = new LinkedHashMap<>();
            if (saved.get("speed") != null) keep.put("speed", current.get("speed"));
            if (saved.get("gameBest") != null) keep.put("gameBest", saved.get("gameBest"));
            if (!keep.isEmpty()) value = json(keep);
        } else {
            if (!onlySpeed || DISPLAY_KEYS.stream().anyMatch(saved::containsKey)) {
                for (String k : DISPLAY_KEYS) {
                    Object v = body == null ? null : body.get(k);
                    next.put(k, v instanceof Boolean b ? b : current.get(k));
                }
            }
            // F70: turning romaji off ends the full-romaji mode of a learner who skipped Kana
            if (Boolean.TRUE.equals(saved.get("fullRomaji")) && !Boolean.FALSE.equals(next.get("romaji"))) next.put("fullRomaji", true);
            Object speed = body != null && body.containsKey("speed") ? body.get("speed") : saved.get("speed");
            if (speed != null) next.put("speed", speedOf(speed));
            if (saved.get("gameBest") != null) next.put("gameBest", saved.get("gameBest")); // F70 Du lịch game best score
            value = next.isEmpty() ? null : json(next);
        }
        try {
            db.update("UPDATE cpath_user_path SET display_json = ? WHERE id = ?", value, mainId);
            List<Map<String, Object>> others = db.queryForList(
                    "SELECT * FROM cpath_user_path WHERE user_id = ? AND status = 'ACTIVE' AND id <> ?", userId, mainId);
            for (Map<String, Object> other : others) {
                Long otherId = ((Number) other.get("id")).longValue();
                Map<?, ?> otherSaved = other.get("display_json") == null ? Map.of() : parse(String.valueOf(other.get("display_json")), Map.of());
                Map<String, Object> otherCurrent = displayOf(other);
                String otherVal = null;
                if (reset) {
                    Map<String, Object> otherKeep = new LinkedHashMap<>();
                    if (otherSaved.get("speed") != null) otherKeep.put("speed", otherCurrent.get("speed"));
                    if (otherSaved.get("gameBest") != null) otherKeep.put("gameBest", otherSaved.get("gameBest"));
                    if (!otherKeep.isEmpty()) otherVal = json(otherKeep);
                } else {
                    Map<String, Object> otherNext = new LinkedHashMap<>();
                    if (!onlySpeed) {
                        for (String k : DISPLAY_KEYS) {
                            if (next.get(k) instanceof Boolean b) otherNext.put(k, b);
                        }
                    } else {
                        for (String k : DISPLAY_KEYS) {
                            if (otherSaved.get(k) instanceof Boolean b) otherNext.put(k, b);
                        }
                    }
                    if (Boolean.TRUE.equals(otherSaved.get("fullRomaji")) && !Boolean.FALSE.equals(otherNext.get("romaji"))) {
                        otherNext.put("fullRomaji", true);
                    }
                    Object otherSpeed = body != null && body.containsKey("speed")
                            ? body.get("speed") : (next.get("speed") != null ? next.get("speed") : otherSaved.get("speed"));
                    if (otherSpeed != null) otherNext.put("speed", speedOf(otherSpeed));
                    if (otherSaved.get("gameBest") != null) otherNext.put("gameBest", otherSaved.get("gameBest"));
                    if (!otherNext.isEmpty()) otherVal = json(otherNext);
                }
                db.update("UPDATE cpath_user_path SET display_json = ? WHERE id = ?", otherVal, otherId);
            }
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            // the deploy DB has not run the F61 schema step yet
            throw new ApiException(ErrorCode.CPATH_DISPLAY_UNAVAILABLE);
        }
        return path(userId);
    }

    /** F61: pauses the main path; progress is kept and choosing the same goal again in the survey resumes it. */
    @Transactional
    public Map<String, Object> pausePath(Long userId) {
        Map<String, Object> path = requirePath(userId);
        db.update("UPDATE cpath_user_path SET status = 'PAUSED' WHERE id = ?", ((Number) path.get("id")).longValue());
        return Map.of("redirectUrl", "/learning");
    }

    /**
     * F61: clears the main path's progress (situations, topics, goal tests, weak lines) and keeps the path, its pace,
     * display and the Sổ tay; CP already earned is not paid twice (the ledger key drops a repeat).
     */
    @Transactional
    public Map<String, Object> restartPath(Long userId, Map<String, Object> body) {
        Object text = body == null ? null : body.get("confirmText");
        if (!(text instanceof String s) || !java.text.Normalizer.normalize(s.strip(), java.text.Normalizer.Form.NFC)
                .toUpperCase(Locale.forLanguageTag("vi")).equals(RESTART_TEXT)) {
            throw new ApiException(ErrorCode.JPATH_RESTART_CONFIRM_MISMATCH);
        }
        Long pathId = ((Number) requirePath(userId).get("id")).longValue();
        for (String table : List.of("cpath_user_situation", "cpath_user_topic", "cpath_user_goal_test", "cpath_user_weak_speech")) {
            db.update("DELETE FROM " + table + " WHERE path_id = ?", pathId);
        }
        new CpathCoreService(db, clock).clear(pathId); // F70: the base-set progress too (the JLPT side is kept)
        return path(userId);
    }

    /** Situations a week assigns: study days of the week x situations per day (same count as the map). */
    private int perWeek(Map<String, Object> path, LocalDate monday) {
        int studyDays = 0;
        for (int i = 0; i < 7; i++) if (!isRest(path, monday.plusDays(i))) studyDays++;
        return Math.max(1, studyDays * ((Number) path.get("daily_situations")).intValue());
    }

    /** F44: the weekly test opens on the test day, or as soon as this week's share is done (learning ahead no longer waits). */
    private boolean weeklyOpen(Long userId, Map<String, Object> path, LocalDate today, String testDay) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return testDay == null || !today.isBefore(LocalDate.parse(testDay)) || weekShareDone(userId, path, monday);
    }

    /** F44/F63: the learner has started at least this week's share of situations since Monday. */
    private boolean weekShareDone(Long userId, Map<String, Object> path, LocalDate monday) {
        return startedSince(path, monday) >= perWeek(path, monday);
    }

    private int startedSince(Map<String, Object> path, LocalDate monday) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM cpath_user_situation WHERE path_id = ? AND study_day >= ?",
                Integer.class, ((Number) path.get("id")).longValue(), monday);
        return n == null ? 0 : n;
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
        boolean open = weeklyOpen(userId, path, today, testDay);
        List<WeeklyQ> qs = open ? weeklyQuestions(userId, path, monday) : List.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weekStart", monday.toString());
        m.put("display", displayOf(path)); // F70: listening speed of the clips
        m.put("testDay", testDay);
        m.put("open", open);
        m.put("passScore", WEEKLY_PASS);
        m.put("passed", weeklyPassed(userId, monday));
        m.put("speakingQuestions", open ? weeklySpeakingQuestions(userId) : List.of());
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
            if (teamNotebook != null) teamNotebook.answered(userId, q.id(), ok); // F126: chép sang sổ "Lộ trình học"
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
        if (pass) {
            // F114 (13/11): two submits at the same moment both read "not passed yet" and both answered "+2 CP"
            // (the credit itself was kept once by the award table's unique key). Lock the path row so the submits
            // run one after the other, and read the award with a locking read so the second sees the first's row.
            db.queryForList("SELECT id FROM cpath_user_path WHERE id = ? FOR UPDATE", ((Number) path.get("id")).longValue());
            boolean already = !db.queryForList("SELECT id FROM pending_credit_award WHERE user_id = ? AND reference_type = ? "
                    + "AND reference_id = ? FOR UPDATE", userId, REF_WEEK, monday.toEpochDay()).isEmpty();
            if (!already) cp = award(userId, WEEK_CP, REF_WEEK, monday.toEpochDay(), "Đạt kiểm tra tuần giao tiếp " + monday);
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
        List<Map<String, Object>> r = db.queryForList("SELECT * FROM cpath_user_goal_test WHERE path_id = ? ORDER BY passed DESC, taken_at DESC LIMIT 1", pathId);
        return r.isEmpty() ? null : r.get(0);
    }

    boolean goalRetryOpen(Long userId) {
        Map<String, Object> p = requirePath(userId);
        Map<String, Object> last = lastGoalTest(((Number) p.get("id")).longValue());
        if (last == null) return true;
        if (num(last.get("passed")) != 0 || Boolean.TRUE.equals(last.get("passed"))) return false;
        return !clock.now().isBefore(dateTimeOf(last.get("taken_at")).plusHours(24));
    }

    @Transactional
    int recordFallbackGoal(Long userId, int score) {
        if (!goalRetryOpen(userId)) throw new ApiException(ErrorCode.CONFLICT, "Bài kiểm tra chưa đến giờ làm lại hoặc đã đạt.");
        Map<String, Object> p = requirePath(userId);
        Long pathId = ((Number) p.get("id")).longValue();
        boolean pass = score >= GOAL_TEST_PASS;
        db.update("INSERT INTO cpath_user_goal_test (path_id, conversation_session_id, score, passed) VALUES (?,NULL,?,?)",
                pathId, score, pass);
        return pass ? award(userId, GOAL_CP, REF_GOAL, pathId, "Đạt bài kiểm tra giao tiếp") : 0;
    }

    /** The goal's AI call test: where to take it, the last result, and when a retry opens (24 h after a fail). */
    @Transactional(readOnly = true)
    public Map<String, Object> goalTest(Long userId) {
        Map<String, Object> p = requirePath(userId);
        String slug = db.queryForObject("SELECT test_scenario_slug FROM cpath_goal WHERE code = ?", String.class, p.get("goal_code"));
        Map<String, Object> last = lastGoalTest(((Number) p.get("id")).longValue());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarioUrl", scenarioUrl(slug));
        m.put("aiAvailable", m.get("scenarioUrl") != null);
        m.put("fallbackUrl", "/learning/comm/weekly?mode=fallback");
        m.put("passScore", GOAL_TEST_PASS);
        m.put("lastScore", last == null ? null : last.get("score"));
        m.put("passed", last != null && (num(last.get("passed")) != 0 || Boolean.TRUE.equals(last.get("passed"))));
        m.put("retryAt", last != null && num(last.get("passed")) == 0 && !Boolean.TRUE.equals(last.get("passed")) && last.get("taken_at") != null
                ? dateTimeOf(last.get("taken_at")).plusHours(24).toString() : null);
        boolean passed = last != null && (num(last.get("passed")) != 0 || Boolean.TRUE.equals(last.get("passed")));
        m.put("certificateUrl", passed ? certificateUrl(p.get("goal_code")) : null);
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
        Map<String, Object> last = lastGoalTest(pathId);
        if (last != null && (num(last.get("passed")) != 0 || Boolean.TRUE.equals(last.get("passed")))) return goalTest(userId);
        if (!goalRetryOpen(userId)) return goalTest(userId);
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

    /**
     * F196 (owner 15/11): the save button on a word / phrase card puts it in the Comm notebook (and the shared
     * "Lộ trình học" notebook) or takes it out; saving again does not raise the wrong count.
     */
    @Transactional
    public Map<String, Object> saveNote(Long userId, String code, boolean save) {
        Map<String, Object> p = requirePath(userId);
        Long pathId = ((Number) p.get("id")).longValue();
        Integer known = db.queryForObject("SELECT COUNT(*) FROM cpath_item WHERE code = ?", Integer.class, code);
        if (known == null || known == 0) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy mục này.");
        if (save) {
            db.update("INSERT INTO cpath_user_note (path_id, item_code, kind) VALUES (?,?,'ITEM') "
                    + "ON DUPLICATE KEY UPDATE wrong_count = wrong_count", pathId, code);
        } else {
            db.update("DELETE FROM cpath_user_note WHERE path_id = ? AND item_code = ?", pathId, code);
        }
        if (teamNotebook != null) teamNotebook.answered(userId, code, !save);
        return Map.of("code", code, "saved", save);
    }

    /** Sổ tay of the main path: missed words, phrases and learner lines, most missed first. */
    @Transactional(readOnly = true)
    public Map<String, Object> notebook(Long userId) {
        if (teamNotebook != null) teamNotebook.ensure(userId); // F126: sổ "Lộ trình học" hiện ngay ở Sổ tay của tôi
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
        return Map.of("items", out, "total", out.size(), "display", displayOf(p));
    }

    static final int GOAL_CP = 20;
    static final String REF_GOAL = "CPATH_GOAL";

    /** The newest passed goal test of the learner (any of their paths): goal name and when, for the certificate. */
    @Transactional(readOnly = true)
    public java.util.Optional<Map<String, Object>> certificate(Long userId) {
        return certificate(userId, null);
    }

    /** F125: the certificate of one goal (a finished path listed on the path page); the newest one when goal is null. */
    @Transactional(readOnly = true)
    public java.util.Optional<Map<String, Object>> certificate(Long userId, String goal) {
        String g = str(goal);
        List<Map<String, Object>> rows = db.queryForList("SELECT g.code, g.name_vi, t.taken_at FROM cpath_user_goal_test t "
                + "JOIN cpath_user_path p ON p.id = t.path_id JOIN cpath_goal g ON g.code = p.goal_code "
                + "WHERE p.user_id = ? AND t.passed = 1" + (g == null || g.isBlank() ? "" : " AND p.goal_code = ?")
                + " ORDER BY t.taken_at DESC LIMIT 1", g == null || g.isBlank() ? new Object[] {userId} : new Object[] {userId, g});
        if (rows.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(Map.of("goal", String.valueOf(rows.get(0).get("code")),
                "nameVi", String.valueOf(rows.get(0).get("name_vi")),
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
