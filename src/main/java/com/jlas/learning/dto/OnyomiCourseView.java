package com.jlas.learning.dto;

import java.util.List;

/**
 * The Onyomi course (Hán Việt → âm On) as one learner sees it: stages in order, each with lessons that
 * unlock one after another and a final test that opens once every lesson of the stage is completed.
 *
 * @param currentLessonId the first unlocked lesson not completed yet; null when every lesson is done
 * @param unrestricted    true for a content reviewer (content admin or admin), who has every lesson and
 *                        final test unlocked to check the content
 */
public record OnyomiCourseView(
        List<Stage> stages,
        int totalLessons,
        int completedLessons,
        int percent,
        Long currentLessonId,
        boolean unrestricted) {

    /** One stage of the course (a unit of the Onyomi textbook). */
    public record Stage(
            Long unitId,
            int order,
            String title,
            String description,
            List<LessonCard> lessons,
            int completedLessons,
            FinalTest finalTest) {
    }

    /**
     * A lesson tile. {@code status} is a {@link OnyomiLessonStatus} name; {@code bestScore} is the best
     * practice-quiz score in percent, null before the first submitted attempt.
     */
    public record LessonCard(
            Long lessonId,
            int order,
            String title,
            String description,
            String status,
            Integer bestScore,
            List<String> sounds,
            List<String> examples) {
    }

    /** The final test of a stage; {@code quizId} is null when the stage has no final test. */
    public record FinalTest(Long quizId, boolean unlocked, boolean passed, Integer bestScore) {
    }
}
