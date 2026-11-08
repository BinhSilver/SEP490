package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathStudyMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * [JLAS-W0108] Input of {@code JpathPacingEngine.estimate}. {@code levels} runs from the open level to the target
 * level; {@code sliders} apply to the open level only (later levels use the recommendation for {@code dailyMinutes}).
 * {@code thirdSkillDay} may be null (auto choice). {@code mode} must not be null.
 */
public record JpathPaceInput(LocalDate today, List<JpathPaceLevel> levels, JpathSliderValues sliders,
        Set<DayOfWeek> skillDays, Set<DayOfWeek> restDays, DayOfWeek thirdSkillDay, LocalDate examDate,
        int kanaRowsLeft, JpathStudyMode mode, int dailyMinutes, boolean enforceTarget) {
}
