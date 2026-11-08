package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathPathStatus;
import java.util.List;

/**
 * [JLAS-W0108] Where a learner stands before the JLPT path: survey, entry answers, newest Smart Test attempt and the
 * current path. Without saved answers the day lists hold the defaults (skill WED, SAT; no rest day).
 *
 * @param goal       survey goal, trimmed and upper-cased; null without a survey
 * @param smartTest  newest IN_PROGRESS or SUBMITTED Smart Test attempt, or null
 * @param pathStatus ACTIVE, else PAUSED, else null
 */
public record JpathEntryStatusView(boolean surveyDone, String goal, Boolean studiedBefore, String kanaChoice,
                                   boolean preferenceDone, List<String> skillDays, List<String> restDays,
                                   SmartTest smartTest, JpathPathStatus pathStatus) {

    public record SmartTest(Long attemptId, String status) {
    }
}
