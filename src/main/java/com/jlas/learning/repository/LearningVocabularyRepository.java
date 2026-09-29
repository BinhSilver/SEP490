package com.jlas.learning.repository;

import com.jlas.dictionary.entity.ContentVocabulary;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only access to a vocabulary entry with its teaching lesson chain, owned by learning. */
public interface LearningVocabularyRepository extends Repository<ContentVocabulary, ContentVocabulary.ContentVocabularyId> {

    /**
     * [JLAS-W0108] Tất cả cầu nối dạy một từ (content_item -> lesson), kèm chuỗi lesson để dựng
     * warm-up resource. Lọc chỉ bài/textbook đã PUBLISHED; sắp xếp ổn định để lấy bài đầu tiên.
     */
    @Query("""
            select cv from ContentVocabulary cv
              join fetch cv.vocabularyEntry v
              join fetch cv.contentItem ci
              join fetch ci.section s
              join fetch s.lesson l
              join fetch l.unit u
              join fetch u.textbook t
              left join fetch l.knowledgeTag kt
            where v.id = :vocabularyEntryId
              and t.status = 'PUBLISHED'
              and l.status = 'PUBLISHED'
            order by t.id, u.orderIndex, l.orderIndex, ci.orderIndex
            """)
    List<ContentVocabulary> findLinksByVocabularyId(@Param("vocabularyEntryId") Long vocabularyEntryId);

    /**
     * [JLAS-W0122] Cầu nối dạy một từ theo expression (chữ mặt chữ) — dùng khi notebook chỉ còn
     * canonical_item dạng "KANJI:<lexeme>" / "VOCAB:<lexeme>" mà không có vocabulary_entry id.
     */
    @Query("""
            select cv from ContentVocabulary cv
              join fetch cv.vocabularyEntry v
              join fetch cv.contentItem ci
              join fetch ci.section s
              join fetch s.lesson l
              join fetch l.unit u
              join fetch u.textbook t
              left join fetch l.knowledgeTag kt
            where v.expression = :expression
              and t.status = 'PUBLISHED'
              and l.status = 'PUBLISHED'
            order by t.id, u.orderIndex, l.orderIndex, ci.orderIndex
            """)
    List<ContentVocabulary> findLinksByExpression(@Param("expression") String expression);
}
