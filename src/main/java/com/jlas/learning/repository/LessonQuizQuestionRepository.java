package com.jlas.learning.repository;

import com.jlas.assessment.entity.AssessmentQuizQuestion;
import com.jlas.assessment.entity.AssessmentQuizQuestion.AssessmentQuizQuestionId;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only access to the questions of a quiz, in presentation order. */
public interface LessonQuizQuestionRepository
        extends Repository<AssessmentQuizQuestion, AssessmentQuizQuestionId> {

    @Query("""
            select link from AssessmentQuizQuestion link
            join fetch link.question
            where link.quiz.id = :quizId
            order by link.orderIndex, link.question.id
            """)
    List<AssessmentQuizQuestion> findWithQuestionByQuizId(@Param("quizId") Long quizId);
}
