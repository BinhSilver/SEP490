package com.jlas.assessment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SmartTestStartRequest(
        @NotBlank String goal,
        String targetLevel,
        @NotBlank String selfReportedLevel,
        @Min(0) @Max(600) Integer studyDurationMonths,
        @Min(0) @Max(1440) Integer dailyStudyMinutes
) { }
