package com.jlas.learning.repository;

import com.jlas.assessment.entity.AssessmentQuiz;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only access to the published {@code LESSON} quiz attached to a catalog lesson. */
public interface LessonQuizRepository extends Repository<AssessmentQuiz, Long> {

    @Query("""
            select quiz from AssessmentQuiz quiz
            where quiz.lesson.id = :lessonId
              and quiz.quizType = 'LESSON'
              and quiz.status = 'PUBLISHED'
            """)
    Optional<AssessmentQuiz> findPublishedLessonQuiz(@Param("lessonId") Long lessonId);
}
