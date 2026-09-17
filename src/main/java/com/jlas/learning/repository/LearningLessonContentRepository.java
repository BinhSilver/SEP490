package com.jlas.learning.repository;

import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.LessonSection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only access to a lesson's content owned by learning; the dictionary module owns the entities. */
public interface LearningLessonContentRepository extends Repository<LessonSection, Long> {

    @Query("""
            select s from LessonSection s
            where s.lesson.id = :lessonId
            order by s.orderIndex, s.id
            """)
    List<LessonSection> findSections(@Param("lessonId") Long lessonId);

    @Query("""
            select ci from ContentItem ci
            join fetch ci.section s
            where s.lesson.id = :lessonId
            order by s.orderIndex, ci.orderIndex, ci.id
            """)
    List<ContentItem> findContentItems(@Param("lessonId") Long lessonId);

    @Query("""
            select g from GrammarPoint g
            where g.lesson.id = :lessonId
            order by g.orderIndex, g.id
            """)
    List<GrammarPoint> findGrammarPoints(@Param("lessonId") Long lessonId);
}
