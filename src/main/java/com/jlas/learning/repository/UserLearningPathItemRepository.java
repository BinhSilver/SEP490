package com.jlas.learning.repository;

import com.jlas.learning.entity.UserLearningPathItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository cho {@link UserLearningPathItem}. */
@Repository
public interface UserLearningPathItemRepository extends JpaRepository<UserLearningPathItem, Long> {
    List<UserLearningPathItem> findByLearningPathIdOrderByOrderIndex(Long learningPathId);

    /** Loads items together with lesson/unit/textbook/knowledge-tag so the response snapshot avoids N+1 reads. */
    @Query("""
            select item from UserLearningPathItem item
            join fetch item.lesson lesson
            join fetch lesson.unit unit
            join fetch unit.textbook textbook
            join fetch lesson.knowledgeTag knowledgeTag
            left join fetch item.grammarPoint grammarPoint
            where item.learningPath.id = :learningPathId
            order by item.orderIndex
            """)
    List<UserLearningPathItem> findWithLessonByLearningPathId(@Param("learningPathId") Long learningPathId);

    /** [JLAS-W0108] Id từ vựng của warm-up item theo danh sách reasonCode (WEAK_KANJI_ITEM / WEAK_VOCAB_ITEM). */
    @Query("""
            select distinct item.vocabularyEntry.id from UserLearningPathItem item
            where item.learningPath.id = :learningPathId
              and item.reasonCode in :reasonCodes
              and item.vocabularyEntry is not null
            order by item.vocabularyEntry.id
            """)
    List<Long> findVocabularyEntryIdsIn(@Param("learningPathId") Long learningPathId,
            @Param("reasonCodes") List<String> reasonCodes);

    /** [JLAS-W0108] Id mẫu ngữ pháp của warm-up item theo reasonCode (WEAK_GRAMMAR_POINT). */
    @Query("""
            select distinct item.grammarPoint.id from UserLearningPathItem item
            where item.learningPath.id = :learningPathId
              and item.reasonCode in :reasonCodes
              and item.grammarPoint is not null
            order by item.grammarPoint.id
            """)
    List<Long> findGrammarPointIdsIn(@Param("learningPathId") Long learningPathId,
            @Param("reasonCodes") List<String> reasonCodes);
}
