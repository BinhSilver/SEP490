package com.jlas.learning.service.impl;

import com.jlas.mission.service.DailyGoalPlanPort;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] F128 (owner 15/11): the "Mục tiêu mỗi ngày" dialog is the learner's own reward goal; study on the JLPT
 * path and the Comm path counts toward it. Read only: it never changes progress, unlock state or the goal row.
 *
 * <p>Units, per learner day:
 * <ul>
 *   <li>new items ("từ mới") — JLPT: distinct items first learned that day (FIRST_RATE or a correct SKIP_KNOWN, the
 *   path's own "đã học mới" rule); Comm: the phrases of each situation finished that day, the words of a topic on
 *   the day its first situation is finished, and the core items learned in a lesson.</li>
 *   <li>quizzes ("bài trắc nghiệm") — JLPT: every check handed in (part checks, weekly tests, Đọc/Nghe sets); Comm:
 *   every situation finished (it ends with the role-play check) and every goal test taken.</li>
 *   <li>minutes ("thời lượng học") — JLPT: card and answer time of the day, one card counting at most 3 minutes so a
 *   tab left open does not count; Comm: 15 minutes per situation finished (the pace the path shows).</li>
 * </ul>
 * A missing table or a failed query counts as nothing for that part; the dialog then shows less, never an error.
 *
 * <p>Since owner 07/11 the interactive path V2 is gone (rules §5), so {@link #resolve} no longer replaces the
 * learner's targets with a package of it: the targets are always the ones the learner saved.
 */
@Slf4j
@Component
public class LearningDailyGoalPlanAdapter implements DailyGoalPlanPort {

    /** Server zone of DATETIME columns (as Hibernate and the JLPT path history use). */
    static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** One card or answer counts at most this long. */
    static final int MAX_EVENT_MS = 180_000;
    /** Comm path pace: "1 tình huống (khoảng 15 phút)". */
    static final int COMM_MINUTES_PER_SITUATION = 15;

    private static final String JLPT_NEW = "SELECT COUNT(*) FROM (SELECT DISTINCT item_type, item_code "
            + "FROM jpath_study_event WHERE user_id = ? AND study_day = ? "
            + "AND (action = 'FIRST_RATE' OR (action = 'SKIP_KNOWN' AND correct = TRUE))) t";
    private static final String JLPT_MS = "SELECT COALESCE(SUM(LEAST(COALESCE(elapsed_ms, 0), " + MAX_EVENT_MS
            + ")), 0) FROM jpath_study_event WHERE user_id = ? AND study_day = ?";
    private static final String JLPT_CHECKS = "SELECT COUNT(*) FROM jpath_check_set "
            + "WHERE user_id = ? AND status = 'SUBMITTED' AND submitted_at >= ? AND submitted_at < ?";
    private static final String COMM_SITUATIONS = "SELECT COUNT(*) FROM cpath_user_situation u "
            + "JOIN cpath_user_path p ON p.id = u.path_id WHERE p.user_id = ? AND u.done_at >= ? AND u.done_at < ?";
    private static final String COMM_PHRASES = "SELECT COUNT(*) FROM cpath_item i "
            + "JOIN cpath_user_situation u ON u.situation_code = i.situation_code "
            + "JOIN cpath_user_path p ON p.id = u.path_id "
            + "WHERE p.user_id = ? AND i.kind = 'PHRASE' AND u.done_at >= ? AND u.done_at < ?";
    private static final String COMM_TOPIC_WORDS = "SELECT COUNT(*) FROM cpath_item i JOIN ("
            + "SELECT u.path_id, s.topic_code FROM cpath_user_situation u "
            + "JOIN cpath_situation s ON s.code = u.situation_code JOIN cpath_user_path p ON p.id = u.path_id "
            + "WHERE p.user_id = ? AND u.done_at IS NOT NULL GROUP BY u.path_id, s.topic_code "
            + "HAVING MIN(u.done_at) >= ? AND MIN(u.done_at) < ?) t ON t.topic_code = i.topic_code "
            + "WHERE i.kind = 'WORD'";
    private static final String COMM_CORE = "SELECT COUNT(*) FROM cpath_user_core c "
            + "JOIN cpath_user_path p ON p.id = c.path_id WHERE p.user_id = ? "
            + "AND (c.source IS NULL OR c.source = 'LESSON') AND c.done_at >= ? AND c.done_at < ?";
    private static final String COMM_GOAL_TESTS = "SELECT COUNT(*) FROM cpath_user_goal_test g "
            + "JOIN cpath_user_path p ON p.id = g.path_id WHERE p.user_id = ? AND g.taken_at >= ? AND g.taken_at < ?";

    private final JdbcTemplate db;

    public LearningDailyGoalPlanAdapter(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public Optional<Targets> resolve(Long userId) {
        return Optional.empty();
    }

    @Override
    public Activity activity(Long userId, LocalDate day, ZoneId zone) {
        if (userId == null || day == null) {
            return Activity.NONE;
        }
        ZoneId learner = zone == null ? SERVER_ZONE : zone;
        LocalDateTime start = day.atStartOfDay(learner).withZoneSameInstant(SERVER_ZONE).toLocalDateTime();
        LocalDateTime end = day.plusDays(1).atStartOfDay(learner).withZoneSameInstant(SERVER_ZONE).toLocalDateTime();

        long jlptMs = number(JLPT_MS, userId, day);
        long situations = number(COMM_SITUATIONS, userId, start, end);
        long vocabulary = number(JLPT_NEW, userId, day)
                + number(COMM_PHRASES, userId, start, end)
                + number(COMM_TOPIC_WORDS, userId, start, end)
                + number(COMM_CORE, userId, start, end);
        long quizzes = number(JLPT_CHECKS, userId, start, end)
                + situations
                + number(COMM_GOAL_TESTS, userId, start, end);
        long minutes = jlptMs / 60_000 + situations * COMM_MINUTES_PER_SITUATION;
        return new Activity(clamp(vocabulary), clamp(quizzes), clamp(minutes));
    }

    private long number(String sql, Object... args) {
        try {
            Number n = db.queryForObject(sql, Number.class, args);
            return n == null ? 0 : Math.max(0, n.longValue());
        } catch (DataAccessException ex) {
            log.debug("Daily goal path activity query failed: {}", ex.getMessage());
            return 0;
        }
    }

    private static int clamp(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }
}
