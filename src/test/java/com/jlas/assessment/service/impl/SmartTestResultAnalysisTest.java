package com.jlas.assessment.service.impl;

import com.jlas.assessment.dto.SmartTestResponse.BatchResultView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmartTestResultAnalysisTest {

    @Test
    void failedHigherProbeIsShownAsBoundaryInsteadOfFlatLevel() {
        String band = AssessmentServiceImpl.abilityBand("N3", List.of(
                batch(1, "N3", 3),
                batch(2, "N3", 5),
                batch(3, "N2", 2)
        ), false);

        assertThat(band).isEqualTo("N3 Thành thạo");
    }

    @Test
    void passedHigherProbeIsShownAsBeginningOfNewLevel() {
        String band = AssessmentServiceImpl.abilityBand("N2", List.of(
                batch(1, "N3", 3),
                batch(2, "N3", 5),
                batch(3, "N2", 4)
        ), false);

        assertThat(band).isEqualTo("N2 Nhập môn");
    }

    @Test
    void higherLevelScoresDetermineBoundaryStage() {
        String[] expected = {"N3 Căn bản", "N3 Căn bản", "N3 Thành thạo",
                "N3 Thành thạo", "N2 Nhập môn", "N2 Căn bản"};
        for (int score = 0; score <= 5; score++) {
            String level = score >= 4 ? "N2" : "N3";
            assertThat(AssessmentServiceImpl.abilityBand(level,
                    List.of(batch(1, "N3", 4), batch(2, "N2", score)), false))
                    .isEqualTo(expected[score]);
        }
    }

    @Test
    void downwardProbeScoresDetermineStageAndWeakEvidence() {
        for (int score = 0; score <= 5; score++) {
            var batches = List.of(batch(1, "N3", 3), batch(2, "N3", 1), batch(3, "N4", score));
            assertThat(AssessmentServiceImpl.abilityBand("N4", batches, false))
                    .isEqualTo("N4 " + (score <= 2 ? "Nhập môn" : score == 3 ? "Căn bản" : "Thành thạo"));
            assertThat(AssessmentServiceImpl.weakLevelEvidence("N4", batches)).isEqualTo(score <= 2);
        }
    }

    @Test
    void sameLevelConfirmationIsBasicAndPartialRemainsProvisional() {
        for (int score : List.of(2, 3)) {
            var batches = List.of(batch(1, "N3", 3), batch(2, "N3", score));
            assertThat(AssessmentServiceImpl.abilityBand("N3", batches, false)).isEqualTo("N3 Căn bản");
            assertThat(AssessmentServiceImpl.weakLevelEvidence("N3", batches)).isFalse();
        }
        assertThat(AssessmentServiceImpl.abilityBand("N3", List.of(batch(1, "N3", 1)), true))
                .isEqualTo("N3 (tạm tính)");
    }

    @Test
    void confidenceDependsOnEvidenceAndPartialState() {
        assertThat(AssessmentServiceImpl.resultConfidence(1, false)).isEqualTo("MEDIUM");
        assertThat(AssessmentServiceImpl.resultConfidence(3, false)).isEqualTo("HIGH");
        assertThat(AssessmentServiceImpl.resultConfidence(3, true)).isEqualTo("LOW");
    }

    @Test
    void readinessIsRelativeToChosenTarget() {
        assertThat(AssessmentServiceImpl.readiness("N2", 3.0, false)).isEqualTo("READY");
        assertThat(AssessmentServiceImpl.readiness("N2", 2.5, false)).isEqualTo("NEAR");
        assertThat(AssessmentServiceImpl.readiness("N2", 1.5, false)).isEqualTo("BUILDING");
        assertThat(AssessmentServiceImpl.readiness("N2", 3.0, true)).isEqualTo("PROVISIONAL");
    }

    @Test
    void richReadinessIsStoredUsingLegacyDatabaseValues() {
        assertThat(AssessmentServiceImpl.persistedReadiness("READY")).isEqualTo("HIGH");
        assertThat(AssessmentServiceImpl.persistedReadiness("STABLE")).isEqualTo("HIGH");
        assertThat(AssessmentServiceImpl.persistedReadiness("NEAR")).isEqualTo("MEDIUM");
        assertThat(AssessmentServiceImpl.persistedReadiness("BUILDING")).isEqualTo("LOW");
        assertThat(AssessmentServiceImpl.persistedReadiness("PROVISIONAL")).isEqualTo("LOW");
    }

    private static BatchResultView batch(int number, String level, int correct) {
        return new BatchResultView(number, level, correct, 5);
    }
}
