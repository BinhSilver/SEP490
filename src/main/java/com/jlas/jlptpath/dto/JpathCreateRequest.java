package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathStudyMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] Creating a path from the confirmation screen. The open level, kana tab and suggested mode are decided
 * on the server again; {@code assessmentAttemptId} null = the newest Smart Test (SMART_TEST) or no attempt to finish
 * (ABANDONED_TEST); {@code examDate} null = no exam.
 */
public record JpathCreateRequest(@NotNull JpathEntrySource source, Long assessmentAttemptId,
        @NotBlank String targetLevel, LocalDate examDate, @NotNull Integer dailyMinutes,
        @NotNull List<String> skillDays, List<String> restDays, @NotNull JpathSliderValues sliders,
        @NotNull JpathStudyMode mode) {
}
