package com.jlas.learning.service.impl;

import com.jlas.common.learning.JlptPathPolicy;
import com.jlas.learning.dto.LearningProfileSnapshot;
import java.util.List;

/** Decides the JLPT starting point from the finalized overall Smart Test band and survey. */
final class LearningStartPolicy {
    private static final List<String> LEVELS = JlptPathPolicy.TARGET_LEVELS;

    private LearningStartPolicy() { }

    static boolean needsMistakeWarmUp(LearningProfileSnapshot profile) {
        if ("BELOW_N5".equals(profile.assessedLevel())) return false;
        // Same-level Căn bản studies the complete target curriculum; its mistakes inform
        // the chapter guidance, not a second scored warm-up before that curriculum.
        if (profile.assessedLevel().equals(profile.targetLevel())) return false;
        String band = profile.overallAbilityBand();
        return band == null || (!band.endsWith(" Nhập môn") && !band.endsWith(" Thành thạo"));
    }

    static String firstGoalLevel(LearningProfileSnapshot profile) {
        if ("BELOW_N5".equals(profile.assessedLevel())) return "N5";
        String band = profile.overallAbilityBand();
        if (band != null && band.endsWith(" Thành thạo")) return profile.targetLevel();
        if (band != null && band.endsWith(" Nhập môn")) {
            String survey = profile.surveyLevel();
            if (LEVELS.contains(survey) && LEVELS.indexOf(survey) <= LEVELS.indexOf(profile.targetLevel())) {
                return survey;
            }
            // UNKNOWN or an unavailable survey level: use the assessed level, never invent one.
            return LEVELS.contains(profile.assessedLevel()) ? profile.assessedLevel() : profile.targetLevel();
        }
        int assessedIndex = LEVELS.indexOf(profile.assessedLevel());
        int targetIndex = LEVELS.indexOf(profile.targetLevel());
        if (assessedIndex < 0 || targetIndex < 0) return profile.targetLevel();
        return LEVELS.get(Math.min(assessedIndex + 1, targetIndex));
    }

    static List<String> goalLevels(LearningProfileSnapshot profile) {
        String first = firstGoalLevel(profile);
        int from = LEVELS.indexOf(first);
        int to = LEVELS.indexOf(profile.targetLevel());
        return from < 0 || to < from ? List.of(profile.targetLevel()) : LEVELS.subList(from, to + 1);
    }
}
