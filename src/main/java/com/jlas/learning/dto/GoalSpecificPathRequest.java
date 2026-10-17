package com.jlas.learning.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GoalSpecificPathRequest(
        @NotNull @Positive Long submissionId,
        @Positive Long assessmentAttemptId) { }
