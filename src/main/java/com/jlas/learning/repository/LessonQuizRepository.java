package com.jlas.learning.repository;

import com.jlas.assessment.entity.AssessmentQuiz;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only access to the published quizzes of catalog lessons and units. */
public interface LessonQuizRepository extends Repository<AssessmentQuiz, Long> {

    @Query("""
            select quiz from AssessmentQuiz quiz
            where quiz.lesson.id = :lessonId
              and quiz.quizType = 'LESSON'
              and quiz.status = 'PUBLISHED'
            """)
    Optional<AssessmentQuiz> findPublishedLessonQuiz(@Param("lessonId") Long lessonId);

    /** The unit's final tests (UNIT_FINAL), oldest first. */
    @Query("""
            select quiz from AssessmentQuiz quiz
            where quiz.unit.id = :unitId
              and quiz.quizType = 'UNIT_FINAL'
              and quiz.status = 'PUBLISHED'
            order by quiz.id
            """)
    List<AssessmentQuiz> findPublishedUnitFinals(@Param("unitId") Long unitId);
}
