package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] Confirmation screen before a path is created: Smart Test result and the prefilled path (open level,
 * target, minutes, days, kana tab, recommended sliders with their limits, pace and combined/split suggestion).
 *
 * @param smartTestInProgress the newest Smart Test is not finished: the page asks "Làm tiếp / Kết thúc"
 * @param smartTest           null without a usable Smart Test result
 * @param levelNoteVi         set when the Smart Test level is not released yet and the path opens lower
 * @param targetOptions       released levels at or above the open level
 */
public record JpathConfirmationView(JpathEntrySource source, Long assessmentAttemptId, boolean smartTestInProgress,
        JpathSmartTestSummary smartTest, String openLevel, String levelNoteVi, String targetLevel,
        List<String> targetOptions, LocalDate examDate, int dailyMinutes, List<Integer> minuteOptions,
        List<String> skillDays, List<String> restDays, boolean kanaTab, JpathSliderValues sliders,
        Map<JpathTab, JpathSliderBoundView> bounds, JpathPaceView pace, JpathModeSuggestion suggestion) {
}
