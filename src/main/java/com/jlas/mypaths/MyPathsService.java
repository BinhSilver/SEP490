package com.jlas.mypaths;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.cpath.CpathService;
import com.jlas.jlptpath.dto.JpathLevelCell;
import com.jlas.jlptpath.dto.JpathPathView;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathPathService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Owner 24/11 "Lộ trình của tôi": every path the learner has, as peers (no main / secondary), at most
 * {@link CpathService#MAX_ACTIVE} studied at once, each with its own pace. Pause and resume work by key ("JLPT" or
 * "COMM:&lt;goal&gt;"); resuming a third path needs {@code pauseKey} for the one to pause, otherwise MY_PATHS_FULL.
 */
@Service
public class MyPathsService {

    public static final String JLPT = "JLPT";
    static final int IDLE_DAYS = 7;

    private final JdbcTemplate db;
    private final CpathService comm;
    private final JpathPathService jpath;
    private final JpathDailyPlanService jpathDay;
    private final MyPathsNotifier notifier;
    private final com.jlas.jlptpath.service.JpathClock clock;

    public MyPathsService(JdbcTemplate db, CpathService comm, JpathPathService jpath, JpathDailyPlanService jpathDay,
            MyPathsNotifier notifier, com.jlas.jlptpath.service.JpathClock clock) {
        this.clock = clock;
        this.db = db;
        this.comm = comm;
        this.jpath = jpath;
        this.jpathDay = jpathDay;
        this.notifier = notifier;
    }

    /** The dashboard: all paths (ACTIVE first), totals and the over-free-time warning. Sends idle / finished notices. */
    public Map<String, Object> overview(Long userId) {
        List<Map<String, Object>> paths = new ArrayList<>();
        // the JLPT card and the communication cards read separate tables: fetch them side by side (remote DB latency)
        java.util.concurrent.CompletableFuture<Map<String, Object>> jlpt =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> jlptRow(userId));
        List<Map<String, Object>> commRows = comm.myPathsRows(userId);
        Map<String, Object> j = jlpt.join();
        if (j != null) paths.add(j);
        paths.addAll(commRows);
        paths.sort((a, b) -> rank(a) - rank(b));
        int active = 0, total = 0;
        boolean allDone = true;
        for (Map<String, Object> p : paths) {
            if (!"ACTIVE".equals(p.get("status"))) continue;
            active++;
            total += ((Number) p.get("minutesPerDay")).intValue();
            if (!Boolean.TRUE.equals(p.get("todayDone"))) allDone = false;
        }
        Integer free = comm.surveyFreeMinutes(userId);
        notices(userId, paths);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", clock.today(userId).toString()); // the study day (demo clock included), not the browser's
        out.put("activeCount", active);
        out.put("maxActive", CpathService.MAX_ACTIVE);
        out.put("freeMinutes", free);
        out.put("totalMinutes", total);
        out.put("overFreeTime", free != null && free > 0 && total > free);
        out.put("todayAllDone", active > 0 && allDone);
        out.put("paths", paths);
        return out;
    }

    /** Paths the learner has (ACTIVE or PAUSED, any kind); the /learning entry opens the dashboard from two. */
    public int pathCount(Long userId) {
        int n = 0;
        try {
            Integer j = db.queryForObject("SELECT COUNT(*) FROM jpath_user_path WHERE user_id = ? AND status IN ('ACTIVE','PAUSED')", Integer.class, userId);
            n += j == null ? 0 : Math.min(1, j);
        } catch (DataAccessException e) {
            // JLPT tables missing: nothing to count
        }
        try {
            Integer c = db.queryForObject("SELECT COUNT(DISTINCT goal_code) FROM cpath_user_path WHERE user_id = ? AND status IN ('ACTIVE','PAUSED')", Integer.class, userId);
            n += c == null ? 0 : c;
        } catch (DataAccessException e) {
            // communication tables missing
        }
        return n;
    }

    /** The learner studies the JLPT path now. */
    public boolean jlptActive(Long userId) {
        return "ACTIVE".equals(jlptStatus(userId));
    }

    /** Pauses one path; returns its title. */
    @Transactional
    public String pause(Long userId, String key) {
        if (JLPT.equals(key)) {
            String status = jlptStatus(userId);
            if (!"ACTIVE".equals(status)) throw new ApiException(ErrorCode.MY_PATH_NOT_FOUND);
            jpath.pauseForGoalChange(userId);
            return "Lộ trình JLPT";
        }
        String goal = commGoal(key);
        if (!comm.pauseGoal(userId, goal)) throw new ApiException(ErrorCode.MY_PATH_NOT_FOUND);
        return titleOf(userId, key);
    }

    /** Resumes one path, pausing {@code pauseKey} first when two already run; returns its title. */
    @Transactional
    public String resume(Long userId, String key, String pauseKey) {
        if (pauseKey != null && pauseKey.equals(key)) pauseKey = null;
        if (JLPT.equals(key)) {
            if (!"PAUSED".equals(jlptStatus(userId))) throw new ApiException(ErrorCode.MY_PATH_NOT_FOUND);
            comm.makeRoomFor(userId, pauseKey);
            jpath.resumePaused(userId);
            return "Lộ trình JLPT";
        }
        if (comm.resumeGoal(userId, commGoal(key), pauseKey) == null) throw new ApiException(ErrorCode.MY_PATH_NOT_FOUND);
        return titleOf(userId, key);
    }

    /** Keys of the paths now studied (for the MY_PATHS_FULL answer). */
    public List<Map<String, Object>> activePaths(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) overview(userId).get("paths")) {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) o;
            if ("ACTIVE".equals(p.get("status"))) out.add(Map.of("key", p.get("key"), "title", p.get("title")));
        }
        return out;
    }

    public String titleOf(Long userId, String key) {
        if (JLPT.equals(key)) return "Lộ trình JLPT";
        for (Map<String, Object> p : comm.myPathsRows(userId)) if (key.equals(p.get("key"))) return (String) p.get("title");
        return key;
    }

    static String commGoal(String key) {
        String g = key == null || !key.startsWith("COMM:") ? null : MyPathsGoalFilter.normalize(key.substring(5));
        if (g == null) throw new ApiException(ErrorCode.MY_PATH_NOT_FOUND);
        return g;
    }

    private static int rank(Map<String, Object> p) {
        return switch (String.valueOf(p.get("status"))) { case "ACTIVE" -> 0; case "PAUSED" -> 1; default -> 2; };
    }

    private String jlptStatus(Long userId) {
        try {
            List<String> s = db.queryForList("SELECT status FROM jpath_user_path WHERE user_id = ? AND status IN ('ACTIVE','PAUSED') "
                    + "ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END, id DESC LIMIT 1", String.class, userId);
            return s.isEmpty() ? null : s.get(0);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private Map<String, Object> jlptRow(Long userId) {
        Map<String, Object> row;
        try {
            List<Map<String, Object>> rows = db.queryForList("SELECT status, target_level, daily_minutes, updated_at FROM jpath_user_path "
                    + "WHERE user_id = ? AND status IN ('ACTIVE','PAUSED') ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END, id DESC LIMIT 1", userId);
            if (rows.isEmpty()) return null;
            row = rows.get(0);
        } catch (DataAccessException e) {
            return null;
        }
        boolean active = "ACTIVE".equals(row.get("status"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", JLPT);
        m.put("kind", "JLPT");
        m.put("goal", "JLPT");
        m.put("title", "Lộ trình JLPT");
        m.put("status", row.get("status"));
        m.put("percent", null);
        m.put("minutesPerDay", ((Number) row.get("daily_minutes")).intValue());
        m.put("finishDate", null);
        m.put("todayMinutesLeft", 0);
        m.put("todayDone", false);
        m.put("restDay", false);
        m.put("pausedSince", active || row.get("updated_at") == null ? null : String.valueOf(row.get("updated_at")).substring(0, 10));
        m.put("focusTopic", null);
        m.put("focusField", null);
        m.put("targetLevel", row.get("target_level"));
        if (active) {
            java.util.concurrent.CompletableFuture<JpathTodayView> todayF = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> { try { return jpathDay.today(userId); } catch (RuntimeException e) { return null; } });
            try {
                JpathPathView v = jpath.current(userId);
                int learned = 0, total = 0;
                for (JpathLevelCell c : v.levels()) { learned += c.learned(); total += c.total(); }
                m.put("percent", total == 0 ? 0 : (int) Math.round(100.0 * learned / total));
                m.put("finishDate", v.levelFinish() == null ? null : v.levelFinish().toString());
                m.put("focusTopic", v.kanaTab() && !v.kanaCompleted() ? "Nhập môn" : v.openLevel());
                if (v.sliders() != null) m.put("minutesPerDay", v.sliders().estimatedMinutes());
            } catch (RuntimeException e) {
                // the dashboard still lists the path
            }
            try {
                JpathTodayView t = todayF.join();
                if (t == null) throw new IllegalStateException("no plan today");
                boolean rest = t.kind() != null && "REST".equals(t.kind().name());
                m.put("restDay", rest);
                m.put("todayDone", t.allDone() || rest);
                m.put("todayMinutesLeft", t.allDone() || rest ? 0 : t.estimatedMinutes());
            } catch (RuntimeException e) {
                // no plan today (e.g. a test day not ready): keep the defaults
            }
        }
        m.put("url", "/learning?view=jlpt");
        m.put("todayUrl", "/learning?view=jlpt");
        m.put("settingsUrl", "/learning/jlpt/settings");
        return m;
    }

    /** Approved notices 4 and 5: seven days without study (once a week) and a finished path (once). */
    private void notices(Long userId, List<Map<String, Object>> paths) {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        for (Map<String, Object> p : paths) {
            String key = (String) p.get("key");
            String title = (String) p.get("title");
            if ("FINISHED".equals(p.get("status"))) {
                notifier.finished(userId, key, title, JLPT.equals(key) ? null : "thêm một nhu cầu giao tiếp khác hoặc lộ trình JLPT");
                continue;
            }
            Object last = p.get("lastStudied");
            if ("ACTIVE".equals(p.get("status")) && last != null
                    && ChronoUnit.DAYS.between(LocalDate.parse(last.toString()), today) >= IDLE_DAYS) {
                notifier.idle(userId, key, title, today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)));
            }
        }
    }

    /** Approved notice 1: a path was added (or a paused one of the same goal came back); says the new daily total. */
    public void notifyAdded(Long userId, String key) {
        try {
            Map<String, Object> o = overview(userId);
            notifier.added(userId, titleOf(userId, key), (Integer) o.get("totalMinutes"), key + "-" + System.currentTimeMillis());
        } catch (RuntimeException e) {
            // best effort
        }
    }

    /** Approved notice 3: the pace of one path changed; says its new finish date. */
    public void notifyPace(Long userId, String key) {
        try {
            for (Object o : (List<?>) overview(userId).get("paths")) {
                @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) o;
                if (!key.equals(p.get("key")) || p.get("finishDate") == null) continue;
                notifier.paceChanged(userId, (String) p.get("title"), LocalDate.parse(p.get("finishDate").toString()),
                        key + "-" + System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            // best effort
        }
    }

    /** The key of the communication path a request means (its {@code ?goal=}, else the newest active one). */
    public String commKey(Long userId, Map<String, Object> body) {
        Object g = body == null ? null : body.get("goal");
        String goal = g == null ? MyPathsGoalFilter.currentGoal() : MyPathsGoalFilter.normalize(String.valueOf(g));
        if (goal == null) {
            List<String> rows = db.queryForList("SELECT goal_code FROM cpath_user_path WHERE user_id = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1", String.class, userId);
            goal = rows.isEmpty() ? null : rows.get(0);
        }
        return goal == null ? null : "COMM:" + goal;
    }
}
