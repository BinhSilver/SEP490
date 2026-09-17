package com.jlas.mission.dto;

/** Daily goal read model: what the learner has done today versus their targets. */
public record DailyGoalProgress(
        int vocabularyDone, int vocabularyTarget,
        int quizDone, int quizTarget,
        int minutesDone, int minutesTarget,
        boolean completed) {
}
