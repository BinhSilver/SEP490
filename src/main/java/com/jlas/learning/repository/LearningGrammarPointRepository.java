package com.jlas.learning.repository;

import com.jlas.dictionary.entity.GrammarPoint;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only access to a grammar point with its parent lesson chain, owned by learning. */
public interface LearningGrammarPointRepository extends Repository<GrammarPoint, Long> {

    @Query("""
            select gp from GrammarPoint gp
            join fetch gp.lesson lesson
            join fetch lesson.unit unit
            join fetch unit.textbook textbook
            left join fetch lesson.knowledgeTag knowledgeTag
            where gp.id = :id
            """)
    Optional<GrammarPoint> findWithLesson(@Param("id") Long id);
}
