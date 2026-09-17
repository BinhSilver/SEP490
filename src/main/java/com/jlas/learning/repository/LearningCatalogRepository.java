package com.jlas.learning.repository;

import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonTag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// Read-only catalog access owned by learning; no changes to the dictionary repository contract.
public interface LearningCatalogRepository extends Repository<Lesson, Long> {
    @Query("""
            select lesson from Lesson lesson
            join fetch lesson.unit unit
            join fetch unit.textbook textbook
            join fetch lesson.knowledgeTag tag
            where textbook.id in :textbookIds
              and textbook.status = 'PUBLISHED'
              and lesson.status = 'PUBLISHED'
              and tag.tagType = 'KNOWLEDGE'
            order by textbook.id, unit.orderIndex, unit.id, lesson.orderIndex, lesson.id
            """)
    List<Lesson> findPublishedLessons(@Param("textbookIds") List<Long> textbookIds);

    @Query("""
            select link from LessonTag link
            join fetch link.tag tag
            where link.lesson.id in :lessonIds and tag.tagType = 'KNOWLEDGE'
            """)
    List<LessonTag> findKnowledgeTags(@Param("lessonIds") List<Long> lessonIds);

    @Query("""
            select lesson from Lesson lesson
            join fetch lesson.unit unit
            join fetch unit.textbook textbook
            join fetch lesson.knowledgeTag tag
            where lesson.id = :lessonId
              and lesson.status = 'PUBLISHED'
            """)
    Optional<Lesson> findPublishedLessonDetail(@Param("lessonId") Long lessonId);
}
