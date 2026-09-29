package com.jlas.mission.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Daily targets; ranges match the CHECK constraints on user_learning_targets. */
public record LearningTargetsRequest(
        @NotNull @Min(5) @Max(100) Integer dailyVocabTarget,
        @NotNull @Min(1) @Max(20) Integer dailyQuizTarget,
        @NotNull @Min(5) @Max(180) Integer dailyStudyMinutes) {
}
