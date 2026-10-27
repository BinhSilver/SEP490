package com.jlas.dictionary.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.flashcard.util.SrsUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Flashcard scheduling for vocabulary in personal, custom, and weakness notebooks. */
@Service
@RequiredArgsConstructor
public class NotebookStudyService {
    private static final int DEFAULT_DAILY_NEW_LIMIT = 30;
    private static final Set<Integer> DAILY_NEW_OPTIONS = Set.of(10, 20, 30, 50, 100);
    private final JdbcTemplate jdbc;

    public record Card(Long vocabularyId, String expression, String reading, String meaning,
                       String exampleJa, String exampleVi, boolean newCard) {}
    public record Session(Card card, int dueCount, int newCount, int newRemaining, int dailyNewLimit,
                          Map<String, String> intervals) {}
    public record Schedule(String rating, LocalDateTime dueAt, int intervalDays, double easeFactor) {}
    private record Progress(BigDecimal easeFactor, int repetitions, int intervalDays, LocalDateTime dueAt) {
        private SrsUtil.State state() { return SrsUtil.State.of(repetitions, intervalDays, easeFactor, dueAt); }
    }

    @Transactional(readOnly = true)
    public Session next(Long userId, Long notebookId) {
        requireStudyNotebook(userId, notebookId);
        LocalDateTime now = LocalDateTime.now();
        int dailyNewLimit = dailyNewLimit(notebookId);
        int dueCount = count("SELECT COUNT(*) FROM user_notebook_review WHERE notebook_id=? AND due_at<=?", notebookId, now);
        int unseen = count("SELECT COUNT(*) FROM user_notebook_entry e LEFT JOIN user_notebook_review r ON r.notebook_id=e.notebook_id AND r.vocabulary_id=e.vocabulary_id WHERE e.notebook_id=? AND r.vocabulary_id IS NULL", notebookId);
        int remaining = Math.max(0, dailyNewLimit - introducedToday(notebookId));
        List<Card> due = jdbc.query("SELECT v.id,v.expression,v.reading,v.meaning,v.example_ja,v.example_vi FROM user_notebook_review r JOIN vocabulary_entry v ON v.id=r.vocabulary_id WHERE r.notebook_id=? AND r.due_at<=? ORDER BY r.due_at,r.vocabulary_id LIMIT 1",
                (rs, row) -> new Card(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), false), notebookId, now);
        if (!due.isEmpty()) return new Session(due.getFirst(), dueCount, unseen, remaining, dailyNewLimit,
                intervals(progress(notebookId, due.getFirst().vocabularyId()).getFirst(), now));
        if (remaining > 0) {
            List<Card> fresh = jdbc.query("SELECT v.id,v.expression,v.reading,v.meaning,v.example_ja,v.example_vi FROM user_notebook_entry e JOIN vocabulary_entry v ON v.id=e.vocabulary_id LEFT JOIN user_notebook_review r ON r.notebook_id=e.notebook_id AND r.vocabulary_id=e.vocabulary_id WHERE e.notebook_id=? AND r.vocabulary_id IS NULL ORDER BY e.created_at,e.vocabulary_id LIMIT 1",
                    (rs, row) -> new Card(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), true), notebookId);
            if (!fresh.isEmpty()) return new Session(fresh.getFirst(), dueCount, unseen, remaining, dailyNewLimit,
                    intervals(new Progress(SrsUtil.DEFAULT_EASINESS, 0, 0, now), now));
        }
        return new Session(null, dueCount, unseen, remaining, dailyNewLimit, Map.of());
    }

    @Transactional
    public int updateDailyNewLimit(Long userId, Long notebookId, int limit) {
        requireStudyNotebook(userId, notebookId);
        if (!DAILY_NEW_OPTIONS.contains(limit))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn 10, 20, 30, 50 hoặc 100 từ mới mỗi ngày.");
        jdbc.update("INSERT INTO user_notebook_study_setting(notebook_id,daily_new_limit) VALUES (?,?) ON DUPLICATE KEY UPDATE daily_new_limit=VALUES(daily_new_limit)", notebookId, limit);
        return limit;
    }

    @Transactional
    public Schedule rate(Long userId, Long notebookId, Long vocabularyId, String rating) {
        requireStudyNotebook(userId, notebookId);
        if (!List.of("AGAIN", "HARD", "GOOD", "EASY").contains(rating == null ? "" : rating))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mức ghi nhớ không hợp lệ.");
        if (count("SELECT COUNT(*) FROM user_notebook_entry WHERE notebook_id=? AND vocabulary_id=?", notebookId, vocabularyId) == 0)
            throw new ApiException(ErrorCode.NOT_FOUND, "Từ không còn trong sổ tay.");

        LocalDateTime now = LocalDateTime.now();
        List<Progress> rows = jdbc.query("SELECT ease_factor,repetitions,interval_days,due_at FROM user_notebook_review WHERE notebook_id=? AND vocabulary_id=? FOR UPDATE",
                (rs, row) -> new Progress(rs.getBigDecimal(1), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4).toLocalDateTime()), notebookId, vocabularyId);
        if (rows.isEmpty()) {
            if (introducedToday(notebookId) >= dailyNewLimit(notebookId))
                throw new ApiException(ErrorCode.CONFLICT, "Đã đủ mục tiêu từ mới hôm nay. Bạn có thể tăng mục tiêu hoặc học tiếp vào ngày mai.");
            jdbc.update("INSERT INTO user_notebook_review(notebook_id,vocabulary_id,first_seen_at,due_at) VALUES (?,?,?,?)", notebookId, vocabularyId, now, now);
        } else if (rows.getFirst().dueAt().isAfter(now)) {
            throw new ApiException(ErrorCode.CONFLICT, "Từ này chưa tới hạn ôn.");
        }

        Progress old = rows.isEmpty() ? new Progress(SrsUtil.DEFAULT_EASINESS, 0, 0, now) : rows.getFirst();
        int quality = switch (rating) { case "AGAIN" -> 1; case "HARD" -> 3; case "GOOD" -> 4; case "EASY" -> 5; default -> throw new ApiException(ErrorCode.VALIDATION_FAILED); };
        SrsUtil.Schedule result = SrsUtil.review(old.state(), quality, now);
        jdbc.update("UPDATE user_notebook_review SET ease_factor=?,repetitions=?,interval_days=?,due_at=?,last_reviewed_at=? WHERE notebook_id=? AND vocabulary_id=?",
                result.easiness(), result.repetitions(), result.intervalDays(), result.nextReviewAt(), now, notebookId, vocabularyId);
        return new Schedule(rating, result.nextReviewAt(), result.intervalDays(), result.easiness().doubleValue());
    }

    private List<Progress> progress(Long notebookId, Long vocabularyId) {
        return jdbc.query("SELECT ease_factor,repetitions,interval_days,due_at FROM user_notebook_review WHERE notebook_id=? AND vocabulary_id=?",
                (rs, row) -> new Progress(rs.getBigDecimal(1), rs.getInt(2), rs.getInt(3), rs.getTimestamp(4).toLocalDateTime()), notebookId, vocabularyId);
    }

    private static Map<String, String> intervals(Progress progress, LocalDateTime now) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        Map<Integer, Integer> preview = SrsUtil.preview(progress.state(), now);
        for (String rating : List.of("AGAIN", "HARD", "GOOD", "EASY")) {
            int quality = switch (rating) { case "AGAIN" -> 1; case "HARD" -> 3; case "GOOD" -> 4; default -> 5; };
            int days = preview.get(quality);
            result.put(rating, days <= 0 ? "Hôm nay" : days + " ngày");
        }
        return result;
    }

    private void requireStudyNotebook(Long userId, Long notebookId) {
        if (userId == null || userId <= 0) throw new ApiException(ErrorCode.UNAUTHORIZED);
        if (notebookId == null || count("SELECT COUNT(*) FROM user_notebook WHERE id=? AND user_id=? AND notebook_type IN ('PERSONAL','CUSTOM')", notebookId, userId) == 0)
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy sổ tay thường.");
    }

    private int introducedToday(Long notebookId) {
        LocalDate today = LocalDate.now();
        return count("SELECT COUNT(*) FROM user_notebook_review WHERE notebook_id=? AND first_seen_at>=? AND first_seen_at<?",
                notebookId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }

    private int dailyNewLimit(Long notebookId) {
        List<Integer> values = jdbc.query("SELECT daily_new_limit FROM user_notebook_study_setting WHERE notebook_id=?",
                (rs, row) -> rs.getInt(1), notebookId);
        return values.isEmpty() ? DEFAULT_DAILY_NEW_LIMIT : values.getFirst();
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

}
