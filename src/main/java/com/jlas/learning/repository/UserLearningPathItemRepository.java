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

    /** [JLAS-W0108] P2 — tìm item theo id + chủ sở hữu (ownership) để ghi tiến độ activity, không lộ item người khác. */
    @Query("""
            select item from UserLearningPathItem item
            where item.id = :itemId and item.learningPath.user.id = :userId
            """)
    java.util.Optional<UserLearningPathItem> findByIdAndUserId(@Param("itemId") Long itemId,
            @Param("userId") Long userId);

    /** [JLAS-W0108] P4 — item thuộc một bài nguồn của user (để ghi assessment PASSED khi quiz đạt, tách
     * khỏi study status). Cùng bài có thể xuất hiện ở nhiều item (review) nên trả List. */
    @Query("""
            select item from UserLearningPathItem item
            where item.lesson.id = :lessonId and item.learningPath.user.id = :userId
            """)
    List<UserLearningPathItem> findByLessonIdAndUserId(@Param("lessonId") Long lessonId,
            @Param("userId") Long userId);

    /** [JLAS-W0108] P4 — item warm-up grammar thuộc một mẫu ngữ pháp của user (để ghi assessment PASSED
     * khi grammar quiz đạt, tách khỏi study status). */
    @Query("""
            select item from UserLearningPathItem item
            where item.grammarPoint.id = :grammarPointId and item.learningPath.user.id = :userId
            """)
    List<UserLearningPathItem> findByGrammarPointIdAndUserId(@Param("grammarPointId") Long grammarPointId,
            @Param("userId") Long userId);

    /** Loads items together with lesson/unit/textbook/knowledge-tag so the response snapshot avoids N+1 reads.
     * [JLAS-W0108] P2c — left join: item có thể có lesson_id NULL (activity tài nguyên khác) nên inner
     * join sẽ bỏ sót; mapper xử lý lesson null. */
    @Query("""
            select item from UserLearningPathItem item
            left join fetch item.lesson lesson
            left join fetch lesson.unit unit
            left join fetch unit.textbook textbook
            left join fetch lesson.knowledgeTag knowledgeTag
            left join fetch item.grammarPoint grammarPoint
            where item.learningPath.id = :learningPathId
            order by item.orderIndex
            """)
    List<UserLearningPathItem> findWithLessonByLearningPathId(@Param("learningPathId") Long learningPathId);

    /** [JLAS-W0108] Id từ vựng của item ôn từ/Kanji theo danh sách reasonCode (WEAK_* / REMEDIATION_*).
     * [JLAS-W0108] P2d — KHÔNG lọc {@code phase = 'WARM_UP'}: mục ôn từ/Kanji do replan REMEDIATE thêm
     * mang phase GOAL_LEARNING nhưng vẫn thuộc membership bộ flashcard ("mục mới thêm chưa nhớ"). */
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

    /** [JLAS-W0108] Id bài học của warm-up item theo reasonCode (WEAK_READING_ITEM / WEAK_LISTENING_ITEM). */
    @Query("""
            select distinct item.lesson.id from UserLearningPathItem item
            where item.learningPath.id = :learningPathId
              and item.phase = 'WARM_UP'
              and item.reasonCode in :reasonCodes
              and item.lesson is not null
            order by item.lesson.id
            """)
    List<Long> findLessonIdsIn(@Param("learningPathId") Long learningPathId,
            @Param("reasonCodes") List<String> reasonCodes);
}
