package com.jlas.dictionary.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.kanjimap.KanjiNotebookStudyContent;
import com.jlas.flashcard.util.SrsUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Notebook study keeps the existing scheduler and adds Kanji to the same due-first queue. */
@Service
@RequiredArgsConstructor
public class NotebookStudyService {
    private static final int DEFAULT_DAILY_NEW_LIMIT = 30;
    private static final Set<Integer> DAILY_NEW_OPTIONS = Set.of(10, 20, 30, 50, 100);
    private final JdbcTemplate jdbc;
    private final KanjiNotebookStudyContent kanjiContent;

    public record Card(Long vocabularyId, String expression, String reading, String meaning,
                       String exampleJa, String exampleVi, boolean newCard, String type,
                       KanjiNotebookStudyContent.Details kanji) {}
    public record Session(Card card, int dueCount, int newCount, int newRemaining, int dailyNewLimit,
                          Map<String, String> intervals) {}
    public record Schedule(String rating, LocalDateTime dueAt, int intervalDays, double easeFactor) {}
    private record Candidate(String type, Long vocabularyId, String character) {}
    private record Progress(BigDecimal easeFactor, int repetitions, int intervalDays, LocalDateTime dueAt) {
        private SrsUtil.State state() { return SrsUtil.State.of(repetitions, intervalDays, easeFactor, dueAt); }
    }

    @Transactional(readOnly = true)
    public Session next(Long userId, Long notebookId) {
        requireStudyNotebook(userId, notebookId);
        LocalDateTime now = LocalDateTime.now();
        int limit = dailyNewLimit(notebookId);
        int dueCount = count("SELECT COUNT(*) FROM user_notebook_review WHERE notebook_id=? AND due_at<=?", notebookId, now)
                + count("SELECT COUNT(*) FROM user_notebook_kanji WHERE notebook_id=? AND first_seen_at IS NOT NULL AND due_at<=?", notebookId, now);
        int unseen = count("SELECT COUNT(*) FROM user_notebook_entry e LEFT JOIN user_notebook_review r ON r.notebook_id=e.notebook_id AND r.vocabulary_id=e.vocabulary_id WHERE e.notebook_id=? AND r.vocabulary_id IS NULL", notebookId)
                + count("SELECT COUNT(*) FROM user_notebook_kanji WHERE notebook_id=? AND first_seen_at IS NULL", notebookId);
        int remaining = Math.max(0, limit - introducedToday(notebookId));
        var due = candidates("""
                SELECT 'VOCABULARY' AS card_type,r.vocabulary_id,NULL AS kanji_character,r.due_at AS sort_at
                FROM user_notebook_review r WHERE r.notebook_id=? AND r.due_at<=?
                UNION ALL
                SELECT 'KANJI',NULL,k.kanji_character,k.due_at FROM user_notebook_kanji k
                WHERE k.notebook_id=? AND k.first_seen_at IS NOT NULL AND k.due_at<=?
                ORDER BY sort_at,card_type,vocabulary_id,kanji_character LIMIT 1
                """, notebookId, now, notebookId, now);
        if (!due.isEmpty()) {
            var item = due.getFirst();
            return new Session(card(item, false), dueCount, unseen, remaining, limit,
                    intervals(progress(notebookId, item).getFirst(), now));
        }
        if (remaining > 0) {
            var fresh = candidates("""
                    SELECT 'VOCABULARY' AS card_type,e.vocabulary_id,NULL AS kanji_character,e.created_at AS sort_at
                    FROM user_notebook_entry e LEFT JOIN user_notebook_review r
                    ON r.notebook_id=e.notebook_id AND r.vocabulary_id=e.vocabulary_id
                    WHERE e.notebook_id=? AND r.vocabulary_id IS NULL
                    UNION ALL
                    SELECT 'KANJI',NULL,k.kanji_character,k.saved_at FROM user_notebook_kanji k
                    WHERE k.notebook_id=? AND k.first_seen_at IS NULL
                    ORDER BY sort_at,card_type,vocabulary_id,kanji_character LIMIT 1
                    """, notebookId, notebookId);
            if (!fresh.isEmpty()) return new Session(card(fresh.getFirst(), true), dueCount, unseen, remaining, limit,
                    intervals(new Progress(SrsUtil.DEFAULT_EASINESS, 0, 0, now), now));
        }
        return new Session(null, dueCount, unseen, remaining, limit, Map.of());
    }

    @Transactional
    public int updateDailyNewLimit(Long userId, Long notebookId, int limit) {
        requireStudyNotebook(userId, notebookId);
        if (!DAILY_NEW_OPTIONS.contains(limit))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn 10, 20, 30, 50 hoặc 100 thẻ mới mỗi ngày.");
        jdbc.update("INSERT INTO user_notebook_study_setting(notebook_id,daily_new_limit) VALUES (?,?) ON DUPLICATE KEY UPDATE daily_new_limit=VALUES(daily_new_limit)", notebookId, limit);
        return limit;
    }

    @Transactional
    public Schedule rate(Long userId, Long notebookId, Long vocabularyId, String rating) {
        lockNotebook(userId, notebookId);
        int quality = quality(rating);
        if (count("SELECT COUNT(*) FROM user_notebook_entry WHERE notebook_id=? AND vocabulary_id=?", notebookId, vocabularyId) == 0)
            throw new ApiException(ErrorCode.NOT_FOUND, "Từ không còn trong sổ tay.");
        LocalDateTime now = LocalDateTime.now();
        var rows = progress(notebookId, new Candidate("VOCABULARY", vocabularyId, null));
        if (rows.isEmpty()) {
            requireNewAllowance(notebookId);
            jdbc.update("INSERT INTO user_notebook_review(notebook_id,vocabulary_id,first_seen_at,due_at) VALUES (?,?,?,?)", notebookId, vocabularyId, now, now);
        } else requireDue(rows.getFirst(), now);
        var result = SrsUtil.review((rows.isEmpty() ? fresh(now) : rows.getFirst()).state(), quality, now);
        jdbc.update("UPDATE user_notebook_review SET ease_factor=?,repetitions=?,interval_days=?,due_at=?,last_reviewed_at=? WHERE notebook_id=? AND vocabulary_id=?",
                result.easiness(), result.repetitions(), result.intervalDays(), result.nextReviewAt(), now, notebookId, vocabularyId);
        return schedule(rating, result);
    }

    @Transactional
    public Schedule rateKanji(Long userId, Long notebookId, String character, String rating) {
        lockNotebook(userId, notebookId);
        int quality = quality(rating);
        var rows = progress(notebookId, new Candidate("KANJI", null, character));
        if (rows.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "Kanji không còn trong sổ tay.");
        LocalDateTime now = LocalDateTime.now();
        boolean unseen = rows.getFirst().dueAt() == null;
        if (unseen) requireNewAllowance(notebookId);
        else requireDue(rows.getFirst(), now);
        var result = SrsUtil.review((unseen ? fresh(now) : rows.getFirst()).state(), quality, now);
        jdbc.update("UPDATE user_notebook_kanji SET ease_factor=?,repetitions=?,interval_days=?,due_at=?,first_seen_at=COALESCE(first_seen_at,?),last_reviewed_at=? WHERE notebook_id=? AND kanji_character=?",
                result.easiness(), result.repetitions(), result.intervalDays(), result.nextReviewAt(), now, now, notebookId, character);
        return schedule(rating, result);
    }

    private Card card(Candidate item, boolean fresh) {
        if ("KANJI".equals(item.type())) {
            var details = kanjiContent.details(item.character());
            return new Card(null, item.character(), null, details.meaning(), null, null, fresh, "KANJI", details);
        }
        return jdbc.queryForObject("SELECT id,expression,reading,meaning,example_ja,example_vi FROM vocabulary_entry WHERE id=?",
                (rs, i) -> new Card(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), fresh, "VOCABULARY", null), item.vocabularyId());
    }

    private List<Candidate> candidates(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> new Candidate(rs.getString("card_type"),
                rs.getObject("vocabulary_id", Long.class), rs.getString("kanji_character")), args);
    }

    private List<Progress> progress(Long notebookId, Candidate item) {
        boolean kanji = "KANJI".equals(item.type());
        String sql = kanji
                ? "SELECT ease_factor,repetitions,interval_days,due_at FROM user_notebook_kanji WHERE notebook_id=? AND kanji_character=?"
                : "SELECT ease_factor,repetitions,interval_days,due_at FROM user_notebook_review WHERE notebook_id=? AND vocabulary_id=?";
        return jdbc.query(sql, (rs, i) -> new Progress(rs.getBigDecimal(1), rs.getInt(2), rs.getInt(3),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toLocalDateTime()),
                notebookId, kanji ? item.character() : item.vocabularyId());
    }

    private static Progress fresh(LocalDateTime now) { return new Progress(SrsUtil.DEFAULT_EASINESS, 0, 0, now); }
    private static Schedule schedule(String rating, SrsUtil.Schedule result) {
        return new Schedule(rating, result.nextReviewAt(), result.intervalDays(), result.easiness().doubleValue());
    }
    private static int quality(String rating) {
        if (rating == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mức ghi nhớ không hợp lệ.");
        return switch (rating) {
            case "AGAIN" -> 1; case "HARD" -> 3; case "GOOD" -> 4; case "EASY" -> 5;
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Mức ghi nhớ không hợp lệ.");
        };
    }
    private static void requireDue(Progress progress, LocalDateTime now) {
        if (progress.dueAt().isAfter(now)) throw new ApiException(ErrorCode.CONFLICT, "Thẻ này chưa tới hạn ôn.");
    }
    private void requireNewAllowance(Long notebookId) {
        if (introducedToday(notebookId) >= dailyNewLimit(notebookId))
            throw new ApiException(ErrorCode.CONFLICT, "Đã đủ mục tiêu thẻ mới hôm nay. Bạn có thể tăng mục tiêu hoặc học tiếp vào ngày mai.");
    }
    private static Map<String, String> intervals(Progress progress, LocalDateTime now) {
        Map<String, String> result = new LinkedHashMap<>();
        var preview = SrsUtil.preview(progress.state(), now);
        for (String rating : List.of("AGAIN", "HARD", "GOOD", "EASY")) {
            int days = preview.get(quality(rating));
            result.put(rating, days <= 0 ? "Hôm nay" : days + " ngày");
        }
        return result;
    }
    private void requireStudyNotebook(Long userId, Long notebookId) {
        if (userId == null || userId <= 0) throw new ApiException(ErrorCode.UNAUTHORIZED);
        if (notebookId == null || count("SELECT COUNT(*) FROM user_notebook WHERE id=? AND user_id=? AND notebook_type IN ('PERSONAL','CUSTOM','WEAKNESS')", notebookId, userId) == 0)
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy sổ tay.");
    }
    private void lockNotebook(Long userId, Long notebookId) {
        requireStudyNotebook(userId, notebookId);
        // Serialize ratings of both card types so they cannot exceed the shared daily allowance.
        jdbc.queryForObject("SELECT id FROM user_notebook WHERE id=? AND user_id=? FOR UPDATE", Long.class, notebookId, userId);
    }
    private int introducedToday(Long notebookId) {
        LocalDate today = LocalDate.now();
        return count("SELECT COUNT(*) FROM user_notebook_review WHERE notebook_id=? AND first_seen_at>=? AND first_seen_at<?",
                notebookId, today.atStartOfDay(), today.plusDays(1).atStartOfDay())
                + count("SELECT COUNT(*) FROM user_notebook_kanji WHERE notebook_id=? AND first_seen_at>=? AND first_seen_at<?",
                notebookId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
    }
    private int dailyNewLimit(Long notebookId) {
        var values = jdbc.query("SELECT daily_new_limit FROM user_notebook_study_setting WHERE notebook_id=?", (rs, i) -> rs.getInt(1), notebookId);
        return values.isEmpty() ? DEFAULT_DAILY_NEW_LIMIT : values.getFirst();
    }
    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
