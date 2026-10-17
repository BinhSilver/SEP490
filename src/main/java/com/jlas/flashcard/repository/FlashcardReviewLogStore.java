package com.jlas.flashcard.repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Nhật ký chấm thẻ (flashcard_review_log) qua JDBC. Ghi nhật ký là best-effort: nếu DB chưa có bảng thì
 * việc chấm thẻ và lịch SRS vẫn hoạt động, chỉ phần thống kê trống.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class FlashcardReviewLogStore {
    private final JdbcTemplate jdbc;

    public record Entry(Long userId, Long cardId, Long setId, int quality, int previousIntervalDays,
                        int intervalDays, boolean scheduled, String source, LocalDateTime reviewedAt) { }

    /** Tổng số lượt chấm và số lượt nhớ (q ≥ 3) trong một khoảng thời gian. */
    public record Totals(long reviews, long passed) { }

    public void append(Entry entry) {
        try {
            jdbc.update("INSERT INTO flashcard_review_log (user_id, card_id, set_id, quality, previous_interval_days, "
                            + "interval_days, scheduled, source, reviewed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    entry.userId(), entry.cardId(), entry.setId(), entry.quality(), entry.previousIntervalDays(),
                    entry.intervalDays(), entry.scheduled(), entry.source(), Timestamp.valueOf(entry.reviewedAt()));
        } catch (DataAccessException e) {
            log.warn("Không ghi được nhật ký ôn flashcard (user {}, card {}): {}", entry.userId(), entry.cardId(), e.getMessage());
        }
    }

    /** Số lượt chấm theo ngày từ {@code from} (bao gồm), chỉ chứa ngày có học. */
    public Map<LocalDate, Long> dailyCounts(Long userId, LocalDate from) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT CAST(reviewed_at AS DATE) AS review_day, COUNT(*) AS total FROM flashcard_review_log "
                            + "WHERE user_id = ? AND reviewed_at >= ? GROUP BY CAST(reviewed_at AS DATE) ORDER BY review_day",
                    rs -> { result.put(rs.getDate("review_day").toLocalDate(), rs.getLong("total")); },
                    userId, Timestamp.valueOf(from.atStartOfDay()));
        } catch (DataAccessException e) {
            log.warn("Không đọc được nhật ký ôn flashcard của user {}: {}", userId, e.getMessage());
        }
        return result;
    }

    /** Chỉ tính lượt chấm thẻ đã đến hạn (scheduled), để tỉ lệ nhớ không bị ôn dồn trong phiên làm đẹp số liệu. */
    public Totals scheduledTotals(Long userId, LocalDateTime from) {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) AS reviews, COALESCE(SUM(CASE WHEN quality >= 3 THEN 1 ELSE 0 END), 0) AS passed "
                            + "FROM flashcard_review_log WHERE user_id = ? AND reviewed_at >= ? AND scheduled = TRUE",
                    (rs, row) -> new Totals(rs.getLong("reviews"), rs.getLong("passed")),
                    userId, Timestamp.valueOf(from));
        } catch (DataAccessException e) {
            log.warn("Không đọc được tỉ lệ nhớ flashcard của user {}: {}", userId, e.getMessage());
            return new Totals(0, 0);
        }
    }
}
