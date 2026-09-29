package com.jlas.learning.repository;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.entity.ConversionRule;
import com.jlas.assessment.entity.RuleExample;
import com.jlas.assessment.entity.RulePattern;
import com.jlas.dictionary.entity.Lesson;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Read-only access to the Onyomi course: its lessons, rule material, quizzes and one learner's results. */
public interface OnyomiCourseRepository extends Repository<Lesson, Long> {

    /** Published Onyomi lessons with their unit and textbook, in course order. */
    @Query("""
            select lesson from Lesson lesson
            join fetch lesson.unit unit
            join fetch unit.textbook textbook
            where lesson.lessonType = 'AMON'
              and lesson.skill = 'ONYOMI'
              and lesson.status = 'PUBLISHED'
              and textbook.status = 'PUBLISHED'
            order by textbook.id, unit.orderIndex, unit.id, lesson.orderIndex, lesson.id
            """)
    List<Lesson> findCourseLessons();

    /** 1 when the lesson is an Onyomi lesson (published or not), else 0. */
    @Query("""
            select count(lesson.id) from Lesson lesson
            where lesson.id = :lessonId
              and lesson.lessonType = 'AMON'
              and lesson.skill = 'ONYOMI'
            """)
    long countCourseLesson(@Param("lessonId") Long lessonId);

    @Query("""
            select rule from ConversionRule rule
            where rule.lesson.id in :lessonIds
            order by rule.lesson.id, rule.displayOrder, rule.id
            """)
    List<ConversionRule> findRules(@Param("lessonIds") Collection<Long> lessonIds);

    @Query("select rule from ConversionRule rule where rule.id = :ruleId")
    Optional<ConversionRule> findRule(@Param("ruleId") Long ruleId);

    @Query("""
            select example from RuleExample example
            join fetch example.kanji
            where example.rule.id in :ruleIds
            order by example.rule.id, example.displayOrder, example.id
            """)
    List<RuleExample> findExamples(@Param("ruleIds") Collection<Long> ruleIds);

    @Query("""
            select pattern from RulePattern pattern
            where pattern.rule.id in :ruleIds
            order by pattern.rule.id, pattern.step
            """)
    List<RulePattern> findPatterns(@Param("ruleIds") Collection<Long> ruleIds);

    /** The EXCEPTION questions of the rules; their context names the kanji that break the rule and why. */
    @Query("""
            select question from AssessmentQuestion question
            where question.conversionRule.id in :ruleIds
              and question.isActive = true
              and question.contextJson like '%"EXCEPTION"%'
            order by question.id
            """)
    List<AssessmentQuestion> findExceptionQuestions(@Param("ruleIds") Collection<Long> ruleIds);

    /** Published quizzes owned by the lessons (LESSON) or units (UNIT_FINAL) of the course. */
    @Query("""
            select quiz.id as id, quiz.quizType as quizType, lesson.id as lessonId, unit.id as unitId
            from AssessmentQuiz quiz
            left join quiz.lesson lesson
            left join quiz.unit unit
            where quiz.status = 'PUBLISHED'
              and (lesson.id in :lessonIds or unit.id in :unitIds)
            order by quiz.id
            """)
    List<QuizRef> findQuizzes(@Param("lessonIds") Collection<Long> lessonIds,
                              @Param("unitIds") Collection<Long> unitIds);

    @Query("""
            select link.quiz.id as quizId, count(link.id.questionId) as questions
            from AssessmentQuizQuestion link
            where link.quiz.id in :quizIds
            group by link.quiz.id
            """)
    List<QuizSize> countQuestions(@Param("quizIds") Collection<Long> quizIds);

    /** The learner's best submitted score per quiz. */
    @Query("""
            select link.quiz.id as quizId, max(attempt.scorePercent) as bestScore
            from AssessmentQuizAttempt link
            join link.attempt attempt
            where attempt.user.id = :userId
              and attempt.status = 'SUBMITTED'
              and link.quiz.id in :quizIds
            group by link.quiz.id
            """)
    List<QuizScore> findBestScores(@Param("userId") Long userId, @Param("quizIds") Collection<Long> quizIds);

    @Query("""
            select progress.lesson.id as lessonId, progress.status as status
            from LessonProgress progress
            where progress.user.id = :userId
              and progress.lesson.id in :lessonIds
            """)
    List<ProgressRef> findProgress(@Param("userId") Long userId, @Param("lessonIds") Collection<Long> lessonIds);

    interface QuizRef {
        Long getId();
        String getQuizType();
        Long getLessonId();
        Long getUnitId();
    }

    interface QuizSize {
        Long getQuizId();
        Long getQuestions();
    }

    interface QuizScore {
        Long getQuizId();
        BigDecimal getBestScore();
    }

    interface ProgressRef {
        Long getLessonId();
        String getStatus();
    }
}
