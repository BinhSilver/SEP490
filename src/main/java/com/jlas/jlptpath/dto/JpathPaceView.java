package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] Pace estimate shown to the learner. {@code tabFinish}/{@code levelFinish} describe the open level,
 * {@code targetFinish} the day the target level is exam-ready; {@code blocked} = the target cannot be reached before the
 * exam with the declared minutes; {@code estimatedMinutes} = minutes of one study day with the sliders;
 * {@code balanced} = the words per day chosen and the Kanji/grammar per day that finish with them (F230).
 */
public record JpathPaceView(Map<JpathTab, LocalDate> tabFinish, LocalDate levelFinish, LocalDate targetFinish,
        boolean blocked, boolean late, boolean overBudget, int estimatedMinutes, int dailyMinutes,
        List<JpathPaceSuggestion> suggestions, int skippedLowPriority, String messageVi,
        List<JpathWarningView> warnings, JpathSliderValues balanced) {
}
