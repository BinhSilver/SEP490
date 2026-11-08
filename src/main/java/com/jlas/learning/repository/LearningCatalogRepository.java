package com.jlas.learning.repository;

import com.jlas.common.learning.DraftStudySourcePolicy;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonTag;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// Read-only catalog access owned by learning; no changes to the dictionary repository contract.
// "Published" here means open for study: a published book's published lessons, or every non-archived lesson of a
// learning-path book still DRAFT at an owner-opened level (§2.28, DraftStudySourcePolicy) — statuses stay as they are.
public interface LearningCatalogRepository extends Repository<Lesson, Long> {
    default List<Lesson> findPublishedLessons(List<Long> textbookIds) {
        return findStudyableLessons(textbookIds, DraftStudySourcePolicy.OPEN_DRAFT_LEVELS);
    }

    @Query("""
            select lesson from Lesson lesson
            join fetch lesson.unit unit
            join fetch unit.textbook textbook
            join fetch lesson.knowledgeTag tag
            where textbook.id in :textbookIds
              and ((textbook.status = 'PUBLISHED' and lesson.status = 'PUBLISHED')
                or (textbook.status = 'DRAFT' and textbook.isLearningPathSource = true
                    and textbook.jlptLevel in :openDraftLevels and lesson.status in ('DRAFT', 'PUBLISHED')))
              and tag.tagType = 'KNOWLEDGE'
            order by textbook.id, unit.orderIndex, unit.id, lesson.orderIndex, lesson.id
            """)
    List<Lesson> findStudyableLessons(@Param("textbookIds") List<Long> textbookIds,
            @Param("openDraftLevels") Collection<String> openDraftLevels);

    @Query("""
            select link from LessonTag link
            join fetch link.tag tag
            where link.lesson.id in :lessonIds and tag.tagType = 'KNOWLEDGE'
            """)
    List<LessonTag> findKnowledgeTags(@Param("lessonIds") List<Long> lessonIds);

    default Optional<Lesson> findPublishedLessonDetail(Long lessonId) {
        return findStudyableLessonDetail(lessonId, DraftStudySourcePolicy.OPEN_DRAFT_LEVELS);
    }

    @Query("""
            select lesson from Lesson lesson
            join fetch lesson.unit unit
            join fetch unit.textbook textbook
            join fetch lesson.knowledgeTag tag
            where lesson.id = :lessonId
              and (lesson.status = 'PUBLISHED'
                or (textbook.status = 'DRAFT' and textbook.isLearningPathSource = true
                    and textbook.jlptLevel in :openDraftLevels and lesson.status = 'DRAFT'))
            """)
    Optional<Lesson> findStudyableLessonDetail(@Param("lessonId") Long lessonId,
            @Param("openDraftLevels") Collection<String> openDraftLevels);
}
