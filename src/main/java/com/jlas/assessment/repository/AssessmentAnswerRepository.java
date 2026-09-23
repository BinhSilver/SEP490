package com.jlas.assessment.repository;

import com.jlas.assessment.entity.AssessmentAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssessmentAnswerRepository extends JpaRepository<AssessmentAnswer, Long> {
    boolean existsByAttemptIdAndQuestionId(Long attemptId, Long questionId);

    List<AssessmentAnswer> findByAttemptId(Long attemptId);

    /** GRAMMAR answers with their primary grammar point's lesson tag fetched, for per-tag aggregation. */
    @Query("""
            select a from AssessmentAnswer a
            join fetch a.question q
            join fetch q.grammarPoint gp
            join fetch gp.lesson l
            join fetch l.knowledgeTag t
            where a.attempt.id = :attemptId
              and q.skill = 'GRAMMAR'
            """)
    List<AssessmentAnswer> findGrammarWithLessonTagByAttemptId(@Param("attemptId") Long attemptId);
}
