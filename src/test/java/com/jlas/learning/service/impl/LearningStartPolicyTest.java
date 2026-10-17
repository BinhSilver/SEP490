package com.jlas.learning.service.impl;

import com.jlas.learning.dto.LearningProfileSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningStartPolicyTest {
    @ParameterizedTest
    @CsvSource({"N5,N4", "N4,N3", "N5,N5", "N4,N4", "N3,N3"})
    void noviceRestartsAtSurveyLevelAcrossJlptLadder(String survey, String target) {
        var profile = profile(survey, target, survey, survey + " Nhập môn");
        assertEquals(survey, LearningStartPolicy.firstGoalLevel(profile));
        assertEquals(survey.equals(target) ? List.of(target) : List.of(survey, target),
                LearningStartPolicy.goalLevels(profile));
        assertFalse(LearningStartPolicy.needsMistakeWarmUp(profile));
    }

    @ParameterizedTest
    @CsvSource({"N5,N4", "N4,N3", "N5,N5", "N4,N4", "N3,N3"})
    void proficientSkipsToTargetAcrossJlptLadder(String assessed, String target) {
        var profile = profile(assessed, target, assessed, assessed + " Thành thạo");
        assertEquals(List.of(target), LearningStartPolicy.goalLevels(profile));
        assertFalse(LearningStartPolicy.needsMistakeWarmUp(profile));
    }

    @Test
    void basicKeepsMistakeWarmUpAndNextTargetStage() {
        var profile = profile("N4", "N3", "N4", "N4 Căn bản");
        assertEquals(List.of("N3"), LearningStartPolicy.goalLevels(profile));
        assertTrue(LearningStartPolicy.needsMistakeWarmUp(profile));
    }

    @ParameterizedTest
    @CsvSource({"N5", "N4", "N3"})
    void basicSameLevelStartsTheFullLevelWithoutSeparateMistakeWarmUp(String level) {
        var profile = profile(level, level, level, level + " Căn bản");
        assertEquals(List.of(level), LearningStartPolicy.goalLevels(profile));
        assertFalse(LearningStartPolicy.needsMistakeWarmUp(profile));
    }

    @Test
    void unknownSurveyUsesAssessedLevelForNovice() {
        var profile = profile("N4", "N3", "UNKNOWN", "N4 Nhập môn");
        assertEquals(List.of("N4", "N3"), LearningStartPolicy.goalLevels(profile));
    }

    private LearningProfileSnapshot profile(String assessed, String target, String survey, String band) {
        return new LearningProfileSnapshot(7L, 91L, 10L, "rev", true, true,
                assessed, target, "JLPT", 30, List.of(), List.of(), survey, band);
    }
}
