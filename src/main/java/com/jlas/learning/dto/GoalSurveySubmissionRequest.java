package com.jlas.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record GoalSurveySubmissionRequest(
        String learningMode,
        @NotEmpty List<@Valid GoalSurveyAnswerRequest> answers
) {}
