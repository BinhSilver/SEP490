package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] The learner's active path: level bar, tabs of the open level, this week, finish estimates (open level),
 * sliders, study mode and display settings. {@code onTrack} = the target is ready before the exam (always true without
 * a usable exam date).
 */
public record JpathPathView(Long pathId, String displayName, String openLevel, String targetLevel, LocalDate examDate,
        boolean onTrack, JpathStage stage, List<JpathLevelCell> levels, List<JpathTabInfo> tabs,
        List<JpathCalendarDayView> week, Map<JpathTab, LocalDate> estimates, LocalDate levelFinish,
        JpathSliderView sliders, JpathStudyMode mode, JpathStudyMode suggestedMode, String suggestionReason,
        boolean kanaTab, boolean kanaCompleted, List<String> skillDays, List<String> restDays, boolean tourDone,
        JpathDisplaySettings display, List<JpathWarningView> warnings) {
}
