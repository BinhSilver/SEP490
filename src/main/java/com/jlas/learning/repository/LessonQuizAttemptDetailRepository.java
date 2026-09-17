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

    /** An open attempt the user can resume for a quiz, so opening it again reuses the same attempt. */
    @Query("""
            select link from AssessmentQuizAttempt link
            join link.attempt attempt
            where attempt.user.id = :userId
              and attempt.status = 'IN_PROGRESS'
              and link.quiz.id = :quizId
            """)
    Optional<AssessmentQuizAttempt> findInProgressByUserIdAndQuizId(
            @Param("userId") Long userId, @Param("quizId") Long quizId);
}
