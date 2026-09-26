package com.jlas.mission.dto;

import java.time.LocalDate;

/** Daily goal read model: what the learner has done on {@code date} versus their targets. */
public record DailyGoalProgress(
        LocalDate date,
        int vocabularyDone, int vocabularyTarget,
        int quizDone, int quizTarget,
        int minutesDone, int minutesTarget,
        int percent,
        boolean completed,
        int reward) {
}
