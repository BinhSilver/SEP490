package com.jlas.learning.dto;

/**
 * The final test of one Onyomi stage.
 *
 * @param quizId          null when the stage has no final test
 * @param bestScore       best score in percent, null before the first submitted attempt
 * @param nextStageLesson first lesson of the next stage, which passing this test unlocks; null for the last stage
 */
public record OnyomiFinalView(
        Long unitId,
        int stageOrder,
        String stageTitle,
        String description,
        Long quizId,
        boolean unlocked,
        boolean passed,
        Integer bestScore,
        int completedLessons,
        int totalLessons,
        OnyomiLessonView.LessonLink nextStageLesson) {
}
