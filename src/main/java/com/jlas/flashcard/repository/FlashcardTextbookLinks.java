package com.jlas.flashcard.repository;

import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bộ flashcard sinh từ một bài giáo trình (tools/flashcard_textbook): bài có tag "VNJ-<mã bài>", bộ có
 * moderation_summary "TEXTBOOK_FLASHCARD:<mã bài>" (phần Từ vựng) hoặc "...-HANTU" (phần Hán tự).
 */
@Repository
public class FlashcardTextbookLinks {
    public record Link(long setId, String title, boolean kanji, long cardCount) { }

    private final JdbcTemplate jdbc;

    public FlashcardTextbookLinks(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Bộ công khai của bài, Từ vựng trước Hán tự; rỗng khi bài không có bộ (hoặc DB chưa có dữ liệu flashcard). */
    public List<Link> forLesson(long lessonId) {
        try {
            return jdbc.query("""
                    SELECT s.id, s.title, s.moderation_summary,
                           (SELECT COUNT(*) FROM flashcard_card c WHERE c.set_id = s.id) cards
                    FROM lesson l
                    JOIN tag t ON t.id = l.knowledge_tag_id AND t.code LIKE 'VNJ-%'
                    JOIN flashcard_set s ON s.moderation_summary IN (CONCAT('TEXTBOOK_FLASHCARD:', SUBSTRING(t.code, 5)),
                                                                    CONCAT('TEXTBOOK_FLASHCARD:', SUBSTRING(t.code, 5), '-HANTU'))
                    WHERE l.id = ? AND s.deleted_at IS NULL AND s.visibility = 'PUBLIC' AND s.is_draft = 0
                    ORDER BY s.moderation_summary
                    """, (rs, n) -> new Link(rs.getLong("id"), rs.getString("title"),
                    rs.getString("moderation_summary").endsWith("-HANTU"), rs.getLong("cards")), lessonId);
        } catch (DataAccessException e) {
            return List.of();
        }
    }
}
