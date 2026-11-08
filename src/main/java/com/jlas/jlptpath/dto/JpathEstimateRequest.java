package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathStudyMode;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] What-if pace estimate from the confirmation screen. {@code sliders} null = recommendation for
 * {@code dailyMinutes}; {@code mode} null = COMBINED; {@code kanaTab} null = no kana tab; {@code examDate} null or past
 * = no exam.
 */
public record JpathEstimateRequest(String openLevel, String targetLevel, LocalDate examDate,
        @NotNull Integer dailyMinutes, List<String> skillDays, List<String> restDays, JpathSliderValues sliders,
        Boolean kanaTab, JpathStudyMode mode) {
}
