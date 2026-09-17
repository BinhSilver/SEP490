package com.jlas.learning.repository;

import com.jlas.assessment.entity.AssessmentQuizAttempt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Access to the quiz-specific half of a quiz attempt, together with its quiz. */
@Repository
public interface LessonQuizAttemptDetailRepository extends JpaRepository<AssessmentQuizAttempt, Long> {

    @Query("""
            select link from AssessmentQuizAttempt link
            join fetch link.quiz
            where link.attempt.id = :attemptId
            """)
    Optional<AssessmentQuizAttempt> findWithQuizByAttemptId(@Param("attemptId") Long attemptId);
}
