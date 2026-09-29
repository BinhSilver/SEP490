package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.SubtitleQualityReview;
import com.jlas.shadowing.model.SubtitleQualitySource;
import com.jlas.shadowing.model.SubtitleQualityStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class SubtitleQualityReviewRepository {
    private final JdbcTemplate jdbc;

    public SubtitleQualityReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(SubtitleQualityReview review) {
        jdbc.update("INSERT INTO subtitle_quality_review "
                        + "(subtitle_id, source_type, source_text, transcription_text, similarity_score, review_status, review_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE source_type = VALUES(source_type), source_text = VALUES(source_text), "
                        + "transcription_text = VALUES(transcription_text), similarity_score = VALUES(similarity_score), "
                        + "review_status = VALUES(review_status), review_reason = VALUES(review_reason), "
                        + "reviewed_by_user_id = NULL, reviewed_at = NULL",
                review.subtitleId(), review.source().name(), review.sourceText(), review.transcriptionText(),
                review.similarityScore(), review.status().name(), review.reason());
    }

    public Map<Long, SubtitleQualityReview> findByVideoId(long videoId) {
        return jdbc.query("SELECT q.subtitle_id, q.source_type, q.source_text, q.transcription_text, q.similarity_score, "
                        + "q.review_status, q.review_reason, q.reviewed_by_user_id, q.reviewed_at "
                        + "FROM subtitle_quality_review q JOIN subtitle s ON s.id = q.subtitle_id "
                        + "WHERE s.video_id = ?", this::mapReview, videoId)
                .stream().collect(Collectors.toMap(SubtitleQualityReview::subtitleId, review -> review));
    }

    public Optional<SubtitleQualityReview> findBySubtitleId(long subtitleId) {
        List<SubtitleQualityReview> reviews = jdbc.query("SELECT subtitle_id, source_type, source_text, transcription_text, "
                        + "similarity_score, review_status, review_reason, reviewed_by_user_id, reviewed_at "
                        + "FROM subtitle_quality_review WHERE subtitle_id = ?", this::mapReview, subtitleId);
        return reviews.stream().findFirst();
    }

    public int countNeedsReview(long videoId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM subtitle_quality_review q "
                        + "JOIN subtitle s ON s.id = q.subtitle_id WHERE s.video_id = ? AND q.review_status = ?",
                Integer.class, videoId, SubtitleQualityStatus.NEEDS_REVIEW.name());
        return count == null ? 0 : count;
    }

    public Set<Long> findBlockedSubtitleIds(long videoId) {
        return Set.copyOf(jdbc.queryForList("SELECT q.subtitle_id FROM subtitle_quality_review q "
                        + "JOIN subtitle s ON s.id = q.subtitle_id WHERE s.video_id = ? AND q.review_status = ?",
                Long.class, videoId, SubtitleQualityStatus.NEEDS_REVIEW.name()));
    }

    public void approve(long subtitleId, long userId, LocalDateTime reviewedAt) {
        jdbc.update("UPDATE subtitle_quality_review SET review_status = ?, review_reason = NULL, reviewed_by_user_id = ?, "
                        + "reviewed_at = ? WHERE subtitle_id = ?",
                SubtitleQualityStatus.APPROVED.name(), userId, Timestamp.valueOf(reviewedAt), subtitleId);
    }

    private SubtitleQualityReview mapReview(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        Timestamp reviewedAt = resultSet.getTimestamp("reviewed_at");
        return new SubtitleQualityReview(
                resultSet.getLong("subtitle_id"),
                SubtitleQualitySource.valueOf(resultSet.getString("source_type")),
                resultSet.getString("source_text"),
                resultSet.getString("transcription_text"),
                resultSet.getDouble("similarity_score"),
                SubtitleQualityStatus.valueOf(resultSet.getString("review_status")),
                resultSet.getString("review_reason"),
                (Long) resultSet.getObject("reviewed_by_user_id"),
                reviewedAt == null ? null : reviewedAt.toLocalDateTime());
    }
}
