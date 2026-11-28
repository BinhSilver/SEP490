package com.jlas.cpath;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.service.JpathClock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] F70 (owner 12/11): the base set ("Bài nền") of the communication path. The set is a filtered list of
 * JLPT items (cpath_core_item → jpath_vocabulary / jpath_grammar), so the JLPT audio, examples and meanings are reused.
 *
 * <ul>
 *   <li>Đời sống uses the COMMON scope; Công việc and BrSE add the WORK scope. Du lịch has no base set (learnt for fun).</li>
 *   <li>Depth: Cơ bản (1) / Sơ cấp (2) / Chuyên sâu (3) keeps the rows whose depth is at most the chosen one; NULL on
 *       the path follows the declared level (Mới bắt đầu → Cơ bản, Sơ cấp → Sơ cấp, Trung cấp → Chuyên sâu).</li>
 *   <li>Each study day: one lesson of {@link #LESSON_SIZE} items per situation of the day, items used in today's
 *       dialogues first; an unfinished lesson of an earlier day comes back first (like F74).</li>
 *   <li>Shared progress: an item learned on the JLPT path counts as done; an item learned here starts on the JLPT path
 *       too (jpath_user_item LEARNING, first review tomorrow).</li>
 *   <li>Items below the shared JLPT level (Smart Test) are "Đã biết – ôn nhanh": one check; pass → done, fail → learnt
 *       in the lessons.</li>
 * </ul>
 * Every read is best-effort: on a database without the F70 tables the path simply has no base set.
 */
@Service
public class CpathCoreService {

    static final int LESSON_SIZE = 10;
    static final int CHECK_SIZE = 20;
    static final int CHECK_PASS = 80;
    static final List<String> DEPTHS = List.of("BASIC", "ELEMENTARY", "ADVANCED");
    static final Map<String, String> DEPTH_LABELS = Map.of("BASIC", "Cơ bản", "ELEMENTARY", "Sơ cấp", "ADVANCED", "Chuyên sâu");
    static final String SRC_LESSON = "LESSON", SRC_JLPT = "JLPT", SRC_CHECK = "CHECK";
    private static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");

    private final JdbcTemplate db;
    private final JpathClock clock;

    public CpathCoreService(JdbcTemplate db, JpathClock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** One base-set item with what a lesson shows. */
    record Item(String code, String refType, String kind, String level, int depth, int sort, String ja, String reading,
                String romaji, String meaningVi, String note, String audio) {}

    // ---------------------------------------------------------------------------------------------- rules

    static boolean hasCore(String goal) {
        return "DAILY_LIFE".equals(goal) || "WORK".equals(goal) || "BA".equals(goal);
    }

    static List<String> scopes(String goal) {
        return "DAILY_LIFE".equals(goal) ? List.of("COMMON") : List.of("COMMON", "WORK");
    }

    /** The path's depth code: the saved one, else from the declared level. */
    static String depthCode(Map<String, Object> path) {
        Object saved = path == null ? null : path.get("depth");
        if (saved != null && DEPTHS.contains(saved.toString())) return saved.toString();
        String level = path == null ? null : (String) path.get("self_level");
        if ("INTERMEDIATE".equals(level)) return "ADVANCED";
        if ("ELEMENTARY".equals(level)) return "ELEMENTARY";
        return "BASIC";
    }

    static int depthRank(String code) { return DEPTHS.indexOf(code) + 1; }

    static boolean below(String level, String shared) {
        if (level == null || shared == null) return false;
        int a = LEVELS.indexOf(level), b = LEVELS.indexOf(shared);
        return a >= 0 && b >= 0 && a < b;
    }

    /** The word as written in a dialogue: drops "（←いる）", "＜する＞", "(する)" notes (same rule as the seed builder). */
    static String baseForm(String expression) {
        return expression == null ? "" : expression.replaceAll("[（(＜<][^）)＞>]*[）)＞>]", "").trim();
    }

    // ---------------------------------------------------------------------------------------------- reads

    /** The goal's items up to {@code maxDepth}, in lesson order, one row per jpath code (the lowest depth wins). */
    List<Item> items(String goal, int maxDepth) {
        if (!hasCore(goal)) return List.of();
        List<String> sc = scopes(goal);
        String in = String.join(",", Collections.nCopies(sc.size(), "?"));
        List<Item> rows = db.query("SELECT c.ref_code, c.ref_type, c.kind, c.level, c.depth, c.sort_order, c.scope, "
                + "v.expression v_ja, v.reading v_reading, v.romaji v_romaji, v.meaning_vi v_meaning, v.word_type_label v_type, v.audio_path v_audio, "
                + "g.pattern g_ja, g.meaning_vi g_meaning, g.formation g_formation "
                + "FROM cpath_core_item c LEFT JOIN jpath_vocabulary v ON c.ref_type = 'VOCAB' AND v.code = c.ref_code "
                + "LEFT JOIN jpath_grammar g ON c.ref_type = 'GRAMMAR' AND g.code = c.ref_code "
                + "WHERE c.scope IN (" + in + ") ORDER BY c.sort_order, c.scope", (rs, i) -> {
            boolean vocab = "VOCAB".equals(rs.getString("ref_type"));
            return new Item(rs.getString("ref_code"), rs.getString("ref_type"), rs.getString("kind"), rs.getString("level"),
                    rs.getInt("depth"), rs.getInt("sort_order"),
                    vocab ? rs.getString("v_ja") : rs.getString("g_ja"),
                    vocab ? rs.getString("v_reading") : null,
                    vocab ? rs.getString("v_romaji") : null,
                    vocab ? rs.getString("v_meaning") : rs.getString("g_meaning"),
                    vocab ? rs.getString("v_type") : rs.getString("g_formation"),
                    vocab ? rs.getString("v_audio") : null);
        }, sc.toArray());
        Map<String, Item> byCode = new LinkedHashMap<>();
        for (Item r : rows) {
            if (r.ja() == null || r.meaningVi() == null) continue; // a jpath row missing on this DB
            Item prev = byCode.get(r.code());
            if (prev == null || r.depth() < prev.depth()) byCode.put(r.code(), r);
        }
        List<Item> out = new ArrayList<>();
        for (Item r : byCode.values()) if (r.depth() <= maxDepth) out.add(r);
        out.sort((a, b) -> a.sort() - b.sort());
        return out;
    }

    private Map<String, Map<String, Object>> progress(long pathId) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> r : db.queryForList("SELECT * FROM cpath_user_core WHERE path_id = ?", pathId)) {
            out.put((String) r.get("ref_code"), r);
        }
        return out;
    }

    /** Codes the learner already learned on the JLPT path (any state once studied, or marked known). */
    Set<String> jlptLearned(Long userId) {
        try {
            return new HashSet<>(db.queryForList("SELECT item_code FROM jpath_user_item WHERE user_id = ? AND item_type IN ('VOCAB','GRAMMAR') "
                    + "AND (first_learned_at IS NOT NULL OR state = 'KNOWN')", String.class, userId));
        } catch (DataAccessException e) {
            return Set.of();
        }
    }

    /** The shared JLPT level (the JLPT path's open level from Smart Test), or null. */
    String sharedLevel(Long userId) {
        try {
            List<String> l = db.queryForList("SELECT open_level FROM jpath_user_path WHERE user_id = ? AND status = 'ACTIVE' "
                    + "ORDER BY id DESC LIMIT 1", String.class, userId);
            return l.isEmpty() ? null : l.get(0);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private static boolean isDone(Map<String, Object> p) { return p != null && p.get("done_at") != null; }

    private static boolean isRest(Map<String, Object> path, LocalDate day) {
        String rest = (String) path.get("rest_days");
        if (rest == null || rest.isBlank()) return false;
        String code = day.getDayOfWeek().name().substring(0, 3);
        for (String r : rest.split(",")) if (r.trim().toUpperCase(Locale.ROOT).startsWith(code)) return true;
        return false;
    }

    private Map<String, Object> mainPath(Long userId) {
        String goal = com.jlas.mypaths.MyPathsGoalFilter.currentGoal();
        if (goal != null) {
            List<Map<String, Object>> own = db.queryForList("SELECT * FROM cpath_user_path WHERE user_id = ? AND goal_code = ? "
                    + "AND status = 'ACTIVE' ORDER BY role = 'MAIN' DESC, id DESC LIMIT 1", userId, goal);
            if (!own.isEmpty()) return own.get(0);
        }
        List<Map<String, Object>> rows;
        try {
            rows = db.queryForList("SELECT * FROM cpath_user_path WHERE user_id = ? AND status = 'ACTIVE' "
                    + "ORDER BY EXISTS (SELECT 1 FROM cpath_user_goal_test gt WHERE gt.path_id = cpath_user_path.id AND gt.passed = TRUE) ASC, "
                    + "role = 'MAIN' DESC, id DESC LIMIT 1", userId);
        } catch (DataAccessException e) {
            rows = db.queryForList("SELECT * FROM cpath_user_path WHERE user_id = ? AND role = 'MAIN' "
                    + "AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1", userId);
        }
        if (rows.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có lộ trình giao tiếp.");
        return rows.get(0);
    }

    /** Marks items learned on the JLPT path (and, after a passed check, the items below the shared level) as done. */
    private void absorb(Long userId, long pathId, List<Item> all, Map<String, Map<String, Object>> prog) {
        Set<String> jlpt = jlptLearned(userId);
        LocalDateTime now = clock.localNow(userId);
        for (Item it : all) {
            Map<String, Object> p = prog.get(it.code());
            if (isDone(p) || !jlpt.contains(it.code())) continue;
            if (p == null) {
                db.update("INSERT INTO cpath_user_core (path_id, ref_code, done_at, source) VALUES (?,?,?,?)", pathId, it.code(), now, SRC_JLPT);
            } else {
                db.update("UPDATE cpath_user_core SET done_at = ?, source = ? WHERE path_id = ? AND ref_code = ?", now, SRC_JLPT, pathId, it.code());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- overview

    /** The path page / settings block: depth, progress, today's lesson and the "Đã biết" check. */
    @Transactional
    public Map<String, Object> overview(Long userId) {
        Map<String, Object> path = mainPath(userId);
        String goal = (String) path.get("goal_code");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", false);
        if (!hasCore(goal)) return m;
        try {
            long pathId = ((Number) path.get("id")).longValue();
            String depth = depthCode(path);
            List<Item> all = items(goal, depthRank(depth));
            if (all.isEmpty()) return m;
            Map<String, Map<String, Object>> prog = progress(pathId);
            // bug 108: GET reads only; POST /today/plan (planDay) absorbs JLPT items and plans the lesson
            List<Map<String, Object>> today = plannedToday(userId, pathId);
            String shared = sharedLevel(userId);
            Map<String, Map<String, Object>> fp = prog;
            long belowOpen = all.stream().filter(i -> below(i.level(), shared) && !isDone(fp.get(i.code()))).count();
            long done = all.stream().filter(i -> isDone(fp.get(i.code()))).count();
            m.put("enabled", true);
            m.put("depth", depth);
            m.put("depthLabel", DEPTH_LABELS.get(depth));
            m.put("depthSaved", path.get("depth") != null);
            m.put("total", all.size());
            m.put("done", done);
            m.put("lessonSize", LESSON_SIZE);
            Map<String, Object> todayMap = new LinkedHashMap<>();
            todayMap.put("count", today.size());
            todayMap.put("done", today.stream().filter(r -> isDone(r)).count());
            todayMap.put("restDay", isRest(path, clock.today(userId)));
            m.put("today", todayMap);
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("level", shared);
            check.put("open", belowOpen);
            check.put("state", path.get("core_check"));
            m.put("knownCheck", shared == null || belowOpen == 0 || "FAILED".equals(path.get("core_check")) ? null : check);
            m.put("depths", depthChoices(goal, path, prog));
            return m;
        } catch (DataAccessException e) {
            m.put("enabled", false);
            return m;
        }
    }

    /** The three depths with their size and an estimate of study days at the current pace (Settings). */
    private List<Map<String, Object>> depthChoices(String goal, Map<String, Object> path, Map<String, Map<String, Object>> prog) {
        int perDay = LESSON_SIZE * Math.max(1, ((Number) path.get("daily_situations")).intValue());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String d : DEPTHS) {
            List<Item> list = items(goal, depthRank(d));
            long left = list.stream().filter(i -> !isDone(prog.get(i.code()))).count();
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", d);
            x.put("labelVi", DEPTH_LABELS.get(d));
            x.put("items", list.size());
            x.put("words", list.stream().filter(i -> "VOCAB".equals(i.kind())).count());
            x.put("grammar", list.stream().filter(i -> "GRAMMAR".equals(i.kind())).count());
            x.put("keigo", list.stream().filter(i -> "KEIGO".equals(i.kind())).count());
            x.put("daysLeft", (left + perDay - 1) / perDay);
            out.add(x);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------- today

    /** Bug 108: today's stored lesson rows (read only). */
    private List<Map<String, Object>> plannedToday(Long userId, long pathId) {
        return db.queryForList("SELECT * FROM cpath_user_core WHERE path_id = ? AND study_day = ?", pathId, clock.today(userId));
    }

    /**
     * Bug 108: what the GETs used to write: marks the JLPT-learned items done and plans today's lesson. Sent once by the
     * page (POST /today/plan) before it reads; a path without base lessons does nothing.
     */
    @Transactional
    public void planDay(Long userId) {
        Map<String, Object> path = mainPath(userId);
        String goal = (String) path.get("goal_code");
        if (!hasCore(goal)) return;
        try {
            long pathId = ((Number) path.get("id")).longValue();
            List<Item> all = items(goal, depthRank(depthCode(path)));
            if (all.isEmpty()) return;
            absorb(userId, pathId, all, progress(pathId));
            planToday(userId, path, all, progress(pathId));
        } catch (DataAccessException e) {
            // base lessons unavailable: the overview already reports enabled=false
        }
    }

    /** Today's lesson rows: kept if planned, else carried over from an earlier day, else the next items (planned now). */
    List<Map<String, Object>> planToday(Long userId, Map<String, Object> path, List<Item> all, Map<String, Map<String, Object>> prog) {
        long pathId = ((Number) path.get("id")).longValue();
        LocalDate day = clock.today(userId);
        List<Map<String, Object>> planned = db.queryForList("SELECT * FROM cpath_user_core WHERE path_id = ? AND study_day = ?", pathId, day);
        if (!planned.isEmpty() || isRest(path, day)) return planned;
        int want = LESSON_SIZE * Math.max(1, ((Number) path.get("daily_situations")).intValue());
        Set<String> inSet = new HashSet<>();
        all.forEach(i -> inSet.add(i.code()));
        int count = 0;
        // I16 (owner 19/11): make-up lessons pile up to at most twice a normal day; the rest moves to later days
        int cap = 2 * want;
        // an unfinished lesson of an earlier day comes back first
        for (Map<String, Object> r : db.queryForList("SELECT ref_code FROM cpath_user_core WHERE path_id = ? AND done_at IS NULL "
                + "AND study_day < ? ORDER BY study_day", pathId, day)) {
            if (count >= cap) break;
            if (!inSet.contains(r.get("ref_code"))) continue; // hidden by a lower depth
            db.update("UPDATE cpath_user_core SET study_day = ? WHERE path_id = ? AND ref_code = ?", day, pathId, r.get("ref_code"));
            count++;
        }
        String shared = sharedLevel(userId);
        boolean checkFailed = "FAILED".equals(path.get("core_check"));
        List<Item> open = new ArrayList<>();
        for (Item it : all) {
            if (prog.containsKey(it.code())) continue;
            if (below(it.level(), shared) && !checkFailed) continue; // waits for the "Đã biết – ôn nhanh" check
            open.add(it);
        }
        // items used in today's dialogues first, then the set order
        String text = todayDialogue(pathId, day);
        List<Item> pick = new ArrayList<>();
        for (Item it : open) {
            String b = baseForm(it.ja());
            if (pick.size() < want && pick.size() + count < cap && "VOCAB".equals(it.refType()) && b.length() >= 2 && text.contains(b)) pick.add(it);
        }
        for (Item it : open) {
            if (pick.size() >= want || pick.size() + count >= cap) break;
            if (!pick.contains(it)) pick.add(it);
        }
        for (Item it : pick) {
            db.update("INSERT INTO cpath_user_core (path_id, ref_code, study_day) VALUES (?,?,?)", pathId, it.code(), day);
        }
        return db.queryForList("SELECT * FROM cpath_user_core WHERE path_id = ? AND study_day = ?", pathId, day);
    }

    /** Lines, words and phrases of the situations planned today (for "items used in today's dialogue first"). */
    private String todayDialogue(long pathId, LocalDate day) {
        StringBuilder sb = new StringBuilder();
        for (String s : db.queryForList("SELECT l.ja FROM cpath_user_situation u JOIN cpath_line l ON l.situation_code = u.situation_code "
                + "WHERE u.path_id = ? AND u.study_day = ?", String.class, pathId, day)) sb.append(s).append('\n');
        return sb.toString();
    }

    /** Today's lesson: the items with what to show, and a meaning quiz (answers are checked on submit). */
    @Transactional
    public Map<String, Object> lesson(Long userId) {
        Map<String, Object> path = mainPath(userId);
        String goal = (String) path.get("goal_code");
        if (!hasCore(goal)) throw new ApiException(ErrorCode.NOT_FOUND, "Lộ trình này không có bài nền.");
        long pathId = ((Number) path.get("id")).longValue();
        List<Item> all;
        try {
            all = items(goal, depthRank(depthCode(path)));
        } catch (DataAccessException e) {
            throw new ApiException(ErrorCode.CPATH_CORE_UNAVAILABLE);
        }
        Map<String, Map<String, Object>> prog = progress(pathId);
        List<Map<String, Object>> today = plannedToday(userId, pathId); // bug 108: planned by POST /today/plan
        Map<String, Item> byCode = new HashMap<>();
        for (Item it : all) byCode.put(it.code(), it);
        List<Item> lessonItems = new ArrayList<>();
        Set<String> doneCodes = new HashSet<>();
        for (Item it : all) {
            for (Map<String, Object> r : today) {
                if (it.code().equals(r.get("ref_code"))) {
                    lessonItems.add(it);
                    if (isDone(r)) doneCodes.add(it.code());
                }
            }
        }
        Random rnd = new Random(userId * 31L + clock.today(userId).toEpochDay());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Item it : lessonItems) {
            Map<String, Object> x = itemView(it);
            x.put("done", doneCodes.contains(it.code()));
            x.put("example", example(it));
            x.put("options", options(it, all, rnd));
            items.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", clock.today(userId).toString());
        m.put("goal", goal);
        m.put("depthLabel", DEPTH_LABELS.get(depthCode(path)));
        m.put("display", CpathService.displayOf(path));
        m.put("items", items);
        m.put("done", !items.isEmpty() && doneCodes.size() == items.size());
        m.put("restDay", isRest(path, clock.today(userId)));
        return m;
    }

    private static Map<String, Object> itemView(Item it) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("code", it.code());
        x.put("kind", it.kind());
        x.put("type", it.refType());
        x.put("level", it.level());
        x.put("ja", it.ja());
        x.put("reading", it.reading());
        x.put("romaji", it.romaji());
        x.put("meaningVi", it.meaningVi());
        x.put("note", it.note());
        x.put("audioUrl", it.audio() == null || it.audio().isBlank() ? null : CpathService.AUDIO_URL + it.audio());
        return x;
    }

    private Map<String, Object> example(Item it) {
        try {
            List<Map<String, Object>> rows = db.queryForList("SELECT ja, ja_furigana, vi, audio_path FROM jpath_example WHERE owner_type = ? "
                    + "AND owner_code = ? AND status <> 'HOLD' ORDER BY role = 'MAIN' DESC, sort_order LIMIT 1", it.refType(), it.code());
            if (rows.isEmpty()) return null;
            Map<String, Object> r = rows.get(0);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ja", r.get("ja"));
            e.put("furigana", r.get("ja_furigana"));
            e.put("vi", r.get("vi"));
            Object a = r.get("audio_path");
            e.put("audioUrl", a == null || a.toString().isBlank() ? null : CpathService.AUDIO_URL + a);
            return e;
        } catch (DataAccessException e) {
            return null;
        }
    }

    /** Four meanings: the item's and three of other items of the same type (VOCAB vs GRAMMAR), shuffled. */
    static List<String> options(Item it, List<Item> pool, Random rnd) {
        List<String> others = new ArrayList<>();
        for (Item o : pool) {
            if (o.code().equals(it.code()) || !o.refType().equals(it.refType())) continue;
            if (o.meaningVi() != null && !o.meaningVi().equals(it.meaningVi()) && !others.contains(o.meaningVi())) others.add(o.meaningVi());
        }
        Collections.shuffle(others, rnd);
        List<String> out = new ArrayList<>(others.subList(0, Math.min(3, others.size())));
        out.add(it.meaningVi());
        Collections.shuffle(out, rnd);
        return out;
    }

    /** Checks the lesson quiz: each right answer marks that item done (here and on the JLPT path); wrong ones stay. */
    @Transactional
    public Map<String, Object> submitLesson(Long userId, Map<String, Object> body) {
        Map<String, Object> path = mainPath(userId);
        String goal = (String) path.get("goal_code");
        long pathId = ((Number) path.get("id")).longValue();
        Object raw = body == null ? null : body.get("answers");
        if (!(raw instanceof Map<?, ?> answers)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu câu trả lời.");
        LocalDate day = clock.today(userId);
        Map<String, Item> byCode = new HashMap<>();
        for (Item it : items(goal, 3)) byCode.put(it.code(), it);
        Set<String> todayCodes = new HashSet<>(db.queryForList("SELECT ref_code FROM cpath_user_core WHERE path_id = ? AND study_day = ?",
                String.class, pathId, day));
        LocalDateTime now = clock.localNow(userId);
        List<Map<String, Object>> results = new ArrayList<>();
        int right = 0;
        for (Map.Entry<?, ?> e : answers.entrySet()) {
            String code = String.valueOf(e.getKey());
            Item it = byCode.get(code);
            if (it == null || !todayCodes.contains(code)) continue; // only today's lesson is checked
            boolean ok = it.meaningVi().equals(String.valueOf(e.getValue()));
            if (ok) {
                right++;
                db.update("UPDATE cpath_user_core SET done_at = COALESCE(done_at, ?), source = COALESCE(source, ?) WHERE path_id = ? AND ref_code = ?",
                        now, SRC_LESSON, pathId, code);
                startOnJlpt(userId, it, now);
            }
            results.add(Map.of("code", code, "correct", ok, "answer", it.meaningVi()));
        }
        Integer left = db.queryForObject("SELECT COUNT(*) FROM cpath_user_core WHERE path_id = ? AND study_day = ? AND done_at IS NULL",
                Integer.class, pathId, day);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("correct", right);
        m.put("total", results.size());
        m.put("results", results);
        m.put("lessonDone", left != null && left == 0);
        return m;
    }

    /** Shared progress: the item starts on the JLPT path as if rated "Nhớ" (first review tomorrow); a row kept as is. */
    private void startOnJlpt(Long userId, Item it, LocalDateTime now) {
        try {
            boolean vocab = "VOCAB".equals(it.refType());
            db.update("INSERT IGNORE INTO jpath_user_item (user_id, item_type, item_code, level, state, repetitions, interval_days, "
                    + "easiness, next_review_at, first_learned_at) VALUES (?,?,?,?, 'LEARNING', ?, ?, 2.50, ?, ?)",
                    userId, it.refType(), it.code(), it.level(), vocab ? 1 : 0, vocab ? 1 : 0, vocab ? now.plusDays(1) : null, now);
        } catch (DataAccessException e) {
            // the JLPT tables are optional for the communication path
        }
    }

    // ---------------------------------------------------------------------------------------------- depth

    /** Changes the depth: a higher one adds items to later days; a lower one hides items not learned (learned stay). */
    @Transactional
    public Map<String, Object> setDepth(Long userId, Map<String, Object> body) {
        Map<String, Object> path = mainPath(userId);
        String depth = body == null || body.get("depth") == null ? null : body.get("depth").toString().toUpperCase(Locale.ROOT);
        if (!DEPTHS.contains(depth)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn Cơ bản, Sơ cấp hoặc Chuyên sâu.");
        long pathId = ((Number) path.get("id")).longValue();
        try {
            db.update("UPDATE cpath_user_path SET depth = ? WHERE id = ?", depth, pathId);
            String goal = (String) path.get("goal_code");
            if (hasCore(goal)) {
                Set<String> keep = new HashSet<>();
                items(goal, depthRank(depth)).forEach(i -> keep.add(i.code()));
                for (String code : db.queryForList("SELECT ref_code FROM cpath_user_core WHERE path_id = ? AND done_at IS NULL", String.class, pathId)) {
                    if (!keep.contains(code)) db.update("DELETE FROM cpath_user_core WHERE path_id = ? AND ref_code = ?", pathId, code);
                }
            }
        } catch (DataAccessException e) {
            throw new ApiException(ErrorCode.CPATH_CORE_UNAVAILABLE);
        }
        return overview(userId);
    }

    // ---------------------------------------------------------------------------------------------- known check

    /** "Đã biết – ôn nhanh": up to 20 items below the shared level, not done yet, with meaning options. */
    @Transactional(readOnly = true)
    public Map<String, Object> knownCheck(Long userId) {
        Map<String, Object> path = mainPath(userId);
        String goal = (String) path.get("goal_code");
        long pathId = ((Number) path.get("id")).longValue();
        String shared = sharedLevel(userId);
        if (!hasCore(goal) || shared == null) throw new ApiException(ErrorCode.NOT_FOUND, "Không có phần đã biết để kiểm tra.");
        List<Item> all = items(goal, depthRank(depthCode(path)));
        Map<String, Map<String, Object>> prog = progress(pathId);
        List<Item> open = new ArrayList<>(all.stream().filter(i -> below(i.level(), shared) && !isDone(prog.get(i.code()))).toList());
        Random rnd = new Random(userId * 17L + clock.today(userId).toEpochDay());
        Collections.shuffle(open, rnd);
        List<Map<String, Object>> qs = new ArrayList<>();
        for (Item it : open.subList(0, Math.min(CHECK_SIZE, open.size()))) {
            Map<String, Object> x = itemView(it);
            x.remove("meaningVi");
            x.put("options", options(it, all, rnd));
            qs.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", shared);
        m.put("open", open.size());
        m.put("pass", CHECK_PASS);
        m.put("display", CpathService.displayOf(path));
        m.put("questions", qs);
        return m;
    }

    /** ≥ 80 %: every item below the shared level counts as done (source CHECK); otherwise they are learnt in lessons. */
    @Transactional
    public Map<String, Object> submitKnownCheck(Long userId, Map<String, Object> body) {
        Map<String, Object> path = mainPath(userId);
        String goal = (String) path.get("goal_code");
        long pathId = ((Number) path.get("id")).longValue();
        String shared = sharedLevel(userId);
        Object raw = body == null ? null : body.get("answers");
        if (!(raw instanceof Map<?, ?> answers) || answers.isEmpty() || shared == null)
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu câu trả lời.");
        List<Item> all = items(goal, depthRank(depthCode(path)));
        Map<String, Item> byCode = new HashMap<>();
        for (Item it : all) byCode.put(it.code(), it);
        int right = 0, total = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<?, ?> e : answers.entrySet()) {
            Item it = byCode.get(String.valueOf(e.getKey()));
            if (it == null || !below(it.level(), shared)) continue;
            total++;
            boolean ok = it.meaningVi().equals(String.valueOf(e.getValue()));
            if (ok) right++;
            results.add(Map.of("code", it.code(), "correct", ok, "answer", it.meaningVi()));
        }
        if (total == 0) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thiếu câu trả lời.");
        int score = Math.round(right * 100f / total);
        boolean passed = score >= CHECK_PASS;
        db.update("UPDATE cpath_user_path SET core_check = ? WHERE id = ?", passed ? "PASSED" : "FAILED", pathId);
        if (passed) {
            LocalDateTime now = clock.localNow(userId);
            Map<String, Map<String, Object>> prog = progress(pathId);
            for (Item it : all) {
                if (!below(it.level(), shared) || prog.containsKey(it.code())) continue;
                db.update("INSERT INTO cpath_user_core (path_id, ref_code, done_at, source) VALUES (?,?,?,?)", pathId, it.code(), now, SRC_CHECK);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", score);
        m.put("passed", passed);
        m.put("pass", CHECK_PASS);
        m.put("results", results);
        return m;
    }

    /** F61 restart: the base-set progress of the path is cleared too (the JLPT side is kept). */
    void clear(long pathId) {
        try {
            db.update("DELETE FROM cpath_user_core WHERE path_id = ?", pathId);
            db.update("UPDATE cpath_user_path SET core_check = NULL WHERE id = ?", pathId);
        } catch (DataAccessException e) {
            // no F70 tables yet
        }
    }

    /** Used by the situation page: the depth rank of each situation inside its topic (1, 2, 3+ → 3). */
    static Map<String, Integer> situationRanks(List<Map<String, Object>> ordered) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, Integer> perTopic = new HashMap<>();
        List<Map<String, Object>> byOrder = new ArrayList<>(ordered);
        byOrder.sort((a, b) -> num(a.get("t_order")) != num(b.get("t_order")) ? num(a.get("t_order")) - num(b.get("t_order"))
                : num(a.get("s_order")) - num(b.get("s_order")));
        for (Map<String, Object> s : byOrder) {
            String t = String.valueOf(s.get("topic_code"));
            int n = perTopic.merge(t, 1, Integer::sum);
            out.put(String.valueOf(s.get("code")), Math.min(3, n));
        }
        return out;
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
}
