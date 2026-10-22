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

    /** Owner 2026-10-21 (OD-36): a provisional or not-enough-data band is placed like Căn bản. */
    @ParameterizedTest
    @CsvSource({"N4 (tạm tính)", "Chưa đủ dữ liệu"})
    void provisionalBandIsPlacedLikeBasic(String band) {
        var provisional = profile("N5", "N4", "N5", band);
        var basic = profile("N5", "N4", "N5", "N5 Căn bản");
        assertEquals(LearningStartPolicy.goalLevels(basic), LearningStartPolicy.goalLevels(provisional));
        assertEquals(LearningStartPolicy.needsMistakeWarmUp(basic), LearningStartPolicy.needsMistakeWarmUp(provisional));
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

    @Test
    void confirmedReviewStartsAtLearnerChosenLevelAndSkipsMistakeWarmup() {
        var profile = profile("N1", "N3", "N3", "N1 Thành thạo").withReviewStartLevel("N5");
        assertEquals("N5", LearningStartPolicy.firstGoalLevel(profile));
        assertEquals(List.of("N5", "N4", "N3"), LearningStartPolicy.goalLevels(profile));
        assertFalse(LearningStartPolicy.needsMistakeWarmUp(profile));
    }

    /** Owner 2026-10-19: accepting a lower Smart Test result starts at the assessed level; the band decides
     * whether that level is studied in full (Nhập môn), reviewed from mistakes (Căn bản) or passed (Thành thạo). */
    @Test
    void acceptingALowerResultWalksAdjacentStagesFromTheAssessedLevelByBand() {
        var basic = profile("N5", "N3", "N4", "N5 Căn bản").withDiscrepancyChoice("ACCEPT_RECOMMENDATION");
        assertEquals(List.of("N4", "N3"), LearningStartPolicy.goalLevels(basic));
        assertEquals("N4", LearningStartPolicy.firstGoalLevel(basic));
        assertTrue(LearningStartPolicy.needsMistakeWarmUp(basic));

        var beginner = profile("N5", "N3", "N4", "N5 Nhập môn").withDiscrepancyChoice("ACCEPT_RECOMMENDATION");
        assertEquals(List.of("N5", "N4", "N3"), LearningStartPolicy.goalLevels(beginner));
        assertFalse(LearningStartPolicy.needsMistakeWarmUp(beginner));

        var proficient = profile("N5", "N3", "N4", "N5 Thành thạo").withDiscrepancyChoice("ACCEPT_RECOMMENDATION");
        assertEquals(List.of("N4", "N3"), LearningStartPolicy.goalLevels(proficient));
        assertFalse(LearningStartPolicy.needsMistakeWarmUp(proficient));
    }

    @Test
    void decliningALowerResultKeepsTheOrdinarySurveyRouteWithoutWarmUp() {
        var declined = profile("N5", "N3", "N4", "N5 Căn bản").withDiscrepancyChoice("DECLINE_RECOMMENDATION");
        assertEquals(List.of("N3"), LearningStartPolicy.goalLevels(declined));
        assertEquals("N3", LearningStartPolicy.firstGoalLevel(declined));
        assertFalse(LearningStartPolicy.needsMistakeWarmUp(declined));
    }

    private LearningProfileSnapshot profile(String assessed, String target, String survey, String band) {
        return new LearningProfileSnapshot(7L, 91L, 10L, "rev", true, true,
                assessed, target, "JLPT", 30, List.of(), List.of(), survey, band);
    }
}
