package com.jlas.pathio;

import com.jlas.cpath.CpathPathFacts;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathLevelInfo;
import com.jlas.jlptpath.util.JpathOpenLevelRule;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * [JLAS-W0108] F240 (mentor, owner 24/11): the entry and exit of each path, computed on every read (nothing stored).
 * <ul>
 *   <li>JLPT entry: Smart Test level + its day, "Nhập môn" when the kana stage comes first, else N5 (test skipped).</li>
 *   <li>JLPT exit: the target level, reached by passing its mock test (80%); done once that level is PASSED.</li>
 *   <li>Communication: self-declared level + need, exit = its situation count, reached by passing the final AI test.</li>
 * </ul>
 */
@Service
public class PathIoService {

    static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    static final int PASS_PERCENT = 80;
    static final String KANA = "Nhập môn";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM");
    static final Map<String, String> SELF_LEVEL = Map.of("BEGINNER", "Mới bắt đầu", "ELEMENTARY", "Sơ cấp",
            "INTERMEDIATE", "Trung cấp");

    private final JdbcTemplate db;
    private final JpathCatalogService catalog;
    private final CpathPathFacts commFacts;

    public PathIoService(JdbcTemplate db, JpathCatalogService catalog, CpathPathFacts commFacts) {
        this.db = db;
        this.catalog = catalog;
        this.commFacts = commFacts;
    }

    /** {paths: {key: io}, levels: [...]} for every path the learner has; with an attempt, also {preview}. */
    public Map<String, Object> overview(Long userId, Long attemptId) {
        Map<String, Object> paths = new LinkedHashMap<>();
        Map<String, Object> j = jlpt(userId);
        if (j != null) paths.put("JLPT", j);
        paths.putAll(comm(userId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paths", paths);
        out.put("levels", levels());
        if (attemptId != null) out.put("preview", preview(userId, attemptId, j));
        return out;
    }

    // ------------------------------------------------------------------------------------------------- JLPT

    private Map<String, Object> jlpt(Long userId) {
        Map<String, Object> row;
        try {
            List<Map<String, Object>> rows = db.queryForList("SELECT id, status, open_level, target_level, kana_tab, kana_completed_at, "
                    + "entry_source, assessment_attempt_id, smart_ability_band FROM jpath_user_path WHERE user_id = ? "
                    + "AND status IN ('ACTIVE','PAUSED') ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END, id DESC LIMIT 1", userId);
            if (rows.isEmpty()) return null;
            row = rows.get(0);
        } catch (DataAccessException e) {
            return null;
        }
        long pathId = ((Number) row.get("id")).longValue();
        boolean smart = "SMART_TEST".equals(str(row.get("entry_source")));
        Map<String, Object> attempt = smart && row.get("assessment_attempt_id") != null
                ? attempt(userId, ((Number) row.get("assessment_attempt_id")).longValue()) : null;
        Map<String, Object> entry;
        if (smart) {
            String level = attempt != null ? str(attempt.get("level"))
                    : JpathOpenLevelRule.openLevel(null, str(row.get("smart_ability_band")));
            entry = smartEntry(level, attempt == null ? null : (LocalDate) attempt.get("day"));
        } else if (truthy(row.get("kana_tab"))) {
            entry = entry("KANA", KANA, "học kana trước", null);
        } else {
            entry = entry("SKIPPED", "N5", "bạn chọn bỏ qua test", null);
        }
        String target = str(row.get("target_level"));
        Map<String, LocalDate> passed = passedLevels(pathId);
        boolean kanaNow = truthy(row.get("kana_tab")) && row.get("kana_completed_at") == null && passed.isEmpty();
        return jlptIo(entry, target, str(row.get("open_level")), passed, kanaNow);
    }

    /** Pure part of the JLPT summary (unit-tested). */
    static Map<String, Object> jlptIo(Map<String, Object> entry, String target, String openLevel,
                                      Map<String, LocalDate> passed, boolean kanaNow) {
        LocalDate doneDay = passed.get(target);
        List<String> passedList = new ArrayList<>();
        for (String l : LEVELS) if (passed.containsKey(l)) passedList.add(l);
        List<String> steps = steps(entry, target);
        String current = null;
        if (doneDay == null) {
            current = kanaNow ? KANA : openLevel;
            if (!kanaNow) {
                for (String l : steps) {
                    if (LEVELS.contains(l) && !passed.containsKey(l)) { current = l; break; }
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "JLPT");
        m.put("entry", entry);
        m.put("exit", jlptExit(target, doneDay));
        m.put("steps", steps);
        m.put("current", current);
        m.put("passed", passedList);
        m.put("progress", progressLine(current, passedList, doneDay));
        m.put("retestUrl", "/survey");
        return m;
    }

    /** The stages a learner goes through: [Nhập môn,] entry level … target. */
    static List<String> steps(Map<String, Object> entry, String target) {
        List<String> out = new ArrayList<>();
        String from = str(entry.get("level"));
        if (!LEVELS.contains(from)) {
            out.add(from);
            from = "N5";
        }
        int a = LEVELS.indexOf(from);
        int b = Math.max(a, LEVELS.indexOf(target));
        for (int i = a; i <= b; i++) out.add(LEVELS.get(i));
        return out;
    }

    static String progressLine(String current, List<String> passed, LocalDate doneDay) {
        if (doneDay != null) return "Đã hoàn thành ngày " + doneDay.format(DAY);
        StringBuilder s = new StringBuilder(current == null ? "" : "Đang học " + current);
        if (!passed.isEmpty()) s.append(s.length() == 0 ? "" : " · ").append("đã qua ").append(String.join(", ", passed));
        return s.length() == 0 ? null : s.toString();
    }

    static Map<String, Object> smartEntry(String level, LocalDate day) {
        return entry("SMART_TEST", level, day == null ? "theo Smart Test" : "theo Smart Test " + day.format(SHORT), day);
    }

    static Map<String, Object> jlptExit(String target, LocalDate doneDay) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", target);
        m.put("label", target);
        m.put("done", doneDay != null);
        m.put("doneDate", doneDay == null ? null : doneDay.toString());
        m.put("condition", doneDay != null ? "Đã hoàn thành ngày " + doneDay.format(DAY)
                : "đạt khi đậu Thi thử " + target + " (≥" + PASS_PERCENT + "%)");
        return m;
    }

    static Map<String, Object> entry(String source, String level, String reason, LocalDate day) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("level", level);
        m.put("label", level);
        m.put("reason", reason);
        m.put("date", day == null ? null : day.toString());
        return m;
    }

    /** A submitted Smart Test of this learner: {level, day}, or null. */
    private Map<String, Object> attempt(Long userId, long attemptId) {
        try {
            List<Map<String, Object>> r = db.queryForList("SELECT a.submitted_at, s.estimated_overall_level FROM assessment_attempt a "
                    + "LEFT JOIN smart_test_session s ON s.attempt_id = a.id WHERE a.id = ? AND a.user_id = ? AND a.status = 'SUBMITTED'",
                    attemptId, userId);
            if (r.isEmpty()) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", JpathOpenLevelRule.openLevel(str(r.get(0).get("estimated_overall_level")), null));
            m.put("day", day(r.get(0).get("submitted_at")));
            return m;
        } catch (DataAccessException e) {
            return null;
        }
    }

    private Map<String, LocalDate> passedLevels(long pathId) {
        Map<String, LocalDate> out = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : db.queryForList("SELECT level, passed_at FROM jpath_user_level WHERE path_id = ? AND stage = 'PASSED'", pathId)) {
                LocalDate d = day(r.get("passed_at"));
                out.put(str(r.get("level")), d == null ? LocalDate.now() : d);
            }
        } catch (DataAccessException e) {
            // no level table: nothing passed
        }
        return out;
    }

    /** The Smart Test result page: what the path is with this result (target from the path, else the survey). */
    private Map<String, Object> preview(Long userId, Long attemptId, Map<String, Object> jlpt) {
        Map<String, Object> a = attempt(userId, attemptId);
        if (a == null) return null;
        String level = catalog.capToReleased(str(a.get("level")));
        // QA 27/11 ST4: the goal of the survey this test belongs to wins; the running path's target only fills in
        // when no survey goal is stored (it showed "Kết thúc: N5" for a test taken toward N3)
        String target;
        try {
            List<String> t = db.queryForList("SELECT target_level FROM user_learning_survey WHERE user_id = ?", String.class, userId);
            target = t.isEmpty() ? null : t.get(0);
        } catch (DataAccessException e) {
            target = null;
        }
        if ((target == null || !LEVELS.contains(target)) && jlpt != null) {
            target = str(((Map<?, ?>) jlpt.get("exit")).get("level"));
        }
        if (target == null || !LEVELS.contains(target) || LEVELS.indexOf(target) < LEVELS.indexOf(level)) target = level;
        Map<String, Object> entry = smartEntry(level, (LocalDate) a.get("day"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "JLPT");
        m.put("entry", entry);
        m.put("exit", jlptExit(target, null));
        m.put("steps", steps(entry, target));
        m.put("hasPath", jlpt != null);
        m.put("pathEntry", jlpt == null ? null : jlpt.get("entry"));
        m.put("retestUrl", "/survey");
        return m;
    }

    private List<Map<String, Object>> levels() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (JpathLevelInfo i : catalog.levels()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("level", i.level());
                m.put("vocab", i.vocabCount());
                m.put("kanji", i.kanjiCount());
                m.put("grammar", i.grammarCount());
                m.put("released", i.released());
                out.add(m);
            }
        } catch (RuntimeException e) {
            // the popup then shows the can-do lines only
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ communication

    private Map<String, Object> comm(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows;
        try {
            rows = db.queryForList("SELECT p.*, g.name_vi FROM cpath_user_path p JOIN cpath_goal g ON g.code = p.goal_code "
                    + "WHERE p.user_id = ? AND p.status IN ('ACTIVE','PAUSED','DONE') "
                    + "ORDER BY CASE p.status WHEN 'ACTIVE' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END, p.id DESC", userId);
        } catch (DataAccessException e) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> p : rows) {
            String goal = str(p.get("goal_code"));
            if (!seen.add(goal)) continue;
            long pathId = ((Number) p.get("id")).longValue();
            int total;
            try {
                total = commFacts.situationCount(p);
            } catch (RuntimeException e) {
                total = 0;
            }
            int done = 0;
            try {
                Integer d = db.queryForObject("SELECT COUNT(*) FROM cpath_user_situation WHERE path_id = ? AND done_at IS NOT NULL",
                        Integer.class, pathId);
                done = d == null ? 0 : d;
            } catch (DataAccessException e) {
                // keep 0
            }
            LocalDate finished = null;
            try {
                List<Object> t = db.queryForList("SELECT MIN(taken_at) FROM cpath_user_goal_test WHERE path_id = ? AND passed = TRUE",
                        Object.class, pathId);
                finished = t.isEmpty() ? null : day(t.get(0));
            } catch (DataAccessException e) {
                // no goal test table
            }
            out.put("COMM:" + goal, commIo(goal, str(p.get("name_vi")), str(p.get("self_level")), total, done, finished));
        }
        return out;
    }

    static Map<String, Object> commIo(String goal, String need, String selfLevel, int total, int done, LocalDate finished) {
        String lvl = SELF_LEVEL.getOrDefault(selfLevel == null ? "" : selfLevel, "Mới bắt đầu");
        Map<String, Object> entry = entry("SELF", lvl, "tự khai", null);
        entry.put("label", lvl + " (tự khai) · " + need);
        entry.put("need", need);
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("situations", total);
        exit.put("label", total + " tình huống");
        exit.put("done", finished != null);
        exit.put("doneDate", finished == null ? null : finished.toString());
        exit.put("condition", finished == null ? "đạt khi đậu bài nói chuyện với AI cuối" : "Đã hoàn thành ngày " + finished.format(DAY));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "COMM");
        m.put("goal", goal);
        m.put("entry", entry);
        m.put("exit", exit);
        m.put("progress", finished != null ? "Đã hoàn thành ngày " + finished.format(DAY)
                : "Đã xong " + Math.min(done, total) + "/" + total + " tình huống");
        return m;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static boolean truthy(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        return o != null && "true".equalsIgnoreCase(String.valueOf(o));
    }

    static LocalDate day(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        if (v instanceof java.time.LocalDateTime t) return t.toLocalDate();
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof LocalDate d) return d;
        String s = String.valueOf(v);
        return s.length() >= 10 ? LocalDate.parse(s.substring(0, 10)) : null;
    }
}
