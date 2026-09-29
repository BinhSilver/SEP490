package com.jlas.learning.repository;

import com.jlas.assessment.entity.AssessmentAnswer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Access to the graded answers of a quiz attempt. */
@Repository
public interface LessonQuizAnswerRepository extends JpaRepository<AssessmentAnswer, Long> {

    @Query("""
            select answer from AssessmentAnswer answer
            join fetch answer.question
            where answer.attempt.id = :attemptId
            order by answer.question.id
            """)
    List<AssessmentAnswer> findWithQuestionByAttemptId(@Param("attemptId") Long attemptId);
}
