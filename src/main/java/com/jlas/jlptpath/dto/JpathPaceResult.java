package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] Result of a pace estimate. {@code tabFinish} and {@code levelFinish} describe the open level;
 * {@code targetFinish} is the day the target level is exam-ready.
 */
public record JpathPaceResult(Map<JpathTab, LocalDate> tabFinish, LocalDate levelFinish, LocalDate targetFinish,
        boolean blocked, boolean late, boolean overBudget, int minutesPerDay, List<JpathPaceSuggestion> suggestions,
        int skippedLowPriority, String messageVi) {
}
