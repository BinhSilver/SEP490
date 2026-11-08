package com.jlas.assessment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * {@code examDate} (F1-3, 2026-10-27): optional JLPT exam date, kept on the learning survey only.
 * {@code focusSkills} (F1-5, 2026-10-28): up to two JLPT skills to focus on (VOCABULARY, READING, LISTENING).
 */
public record SmartTestStartRequest(
        @NotBlank String goal,
        String targetLevel,
        @NotBlank String selfReportedLevel,
        @Min(0) @Max(600) Integer studyDurationMonths,
        @Min(0) @Max(1440) Integer dailyStudyMinutes,
        LocalDate examDate,
        java.util.List<String> focusSkills
) {
    public SmartTestStartRequest(String goal, String targetLevel, String selfReportedLevel, Integer studyDurationMonths,
            Integer dailyStudyMinutes) {
        this(goal, targetLevel, selfReportedLevel, studyDurationMonths, dailyStudyMinutes, null, null);
    }

    public SmartTestStartRequest(String goal, String targetLevel, String selfReportedLevel, Integer studyDurationMonths,
            Integer dailyStudyMinutes, LocalDate examDate) {
        this(goal, targetLevel, selfReportedLevel, studyDurationMonths, dailyStudyMinutes, examDate, null);
    }
}
