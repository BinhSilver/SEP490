package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathStudyMode;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] The settings page. Without an ACTIVE path only {@code display}, the reminder fields and
 * {@code minuteOptions} are filled (schedule fields null/empty, {@code dailyMinutes} 0). {@code examDate} is null once
 * the exam has passed (the pace then carries EXAM_DATE_PASSED); {@code thirdSkillDay} is the stored day or, when
 * {@code thirdSkillDayAuto}, the day chosen automatically; {@code pace} uses the learner's sliders (lateness is a
 * warning); {@code lowerLevels} = released levels below the open level.
 */
public record JpathSettingsView(String targetLevel, List<String> targetOptions, LocalDate examDate,
        JpathPaceView pace, int dailyMinutes, List<Integer> minuteOptions, List<String> skillDays,
        List<String> restDays, String thirdSkillDay, boolean thirdSkillDayAuto, JpathStage stage,
        JpathStudyMode studyMode, JpathModeSuggestion suggestion, JpathDisplaySettings display, boolean reminderWeb,
        boolean reminderEmail, boolean emailAvailable, String reminderTime, JpathSliderView sliders,
        String openLevel, List<String> lowerLevels) {
}
