package com.jlas.common.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JlptPathPolicyTest {

    private final JlptPathPolicy policy = new JlptPathPolicy();

    @ParameterizedTest
    @CsvSource({
            "BELOW_N5,N5,true,N5,FOUNDATION|N5",
            "BELOW_N5,N4,true,N4,FOUNDATION|N5|N4",
            "BELOW_N5,N3,false,N4,",
            "N5,N5,true,N5,N5",
            "N5,N4,true,N4,N4",
            "N5,N3,false,N4,",
            "N4,N4,true,N4,N4",
            "N4,N3,true,N3,N3",
            "N4,N2,false,N3,",
            "N3,N3,true,N3,N3",
            "N3,N2,false,N3,",
            "N3,N1,false,N3,",
            "N2,N1,false,N3,"
    })
    void targetMatrix(String assessed, String requested, boolean allowed,
            String recommended, String stageText) {
        TargetEligibility result = policy.evaluate(assessed, requested);

        assertThat(result.allowed()).isEqualTo(allowed);
        assertThat(result.recommendedTarget()).isEqualTo(recommended);
        assertThat(String.join("|", result.stageLevels())).isEqualTo(stageText == null ? "" : stageText);
        assertThat(result.requiresTargetConfirmation()).isEqualTo(!allowed);
    }

    @ParameterizedTest
    @CsvSource({"FROM_ZERO,N4,FOUNDATION|N5|N4", "UNKNOWN,N5,N5"})
    void aliasesAndTemporaryUnknownOptionsAreHandled(String assessed, String requested, String stages) {
        TargetEligibility result = policy.evaluate(assessed, requested);

        assertThat(result.allowed()).isTrue();
        assertThat(String.join("|", result.stageLevels())).isEqualTo(stages);
    }

    @Test
    void recommendsOnlySupportedNextRoutes() {
        assertThat(policy.nextTargetAfter("N5")).contains("N4");
        assertThat(policy.nextTargetAfter("N4")).contains("N3");
        assertThat(policy.nextTargetAfter("N3")).isEmpty();
        assertThat(policy.nextTargetAfter("N2")).isEmpty();
    }

    @Test
    void n1HasNoHigherJlptTarget() {
        assertThat(policy.nextTargetAfter("N1")).isEmpty();
    }

    @Test
    void unknownSurveyCannotOfferAnUnsupportedN2Path() {
        TargetEligibility result = policy.evaluate("UNKNOWN", "N2");
        assertThat(result.allowed()).isFalse();
        assertThat(result.stageLevels()).isEmpty();
        assertThat(result.reasonCode()).isEqualTo("PATH_NOT_SUPPORTED");
    }

    @Test
    void lowerAssessmentRequiresAnExplicitChoiceAndKeepsTheSurveyTargetOnDecline() {
        assertThat(policy.confirmedStages("N3", "N5", "N3", JlptPathPolicy.Choice.ACCEPT_RECOMMENDATION))
                .isEqualTo(List.of("N5", "N4", "N3"));
        assertThat(policy.confirmedStages("N3", "N5", "N3", JlptPathPolicy.Choice.DECLINE_RECOMMENDATION))
                .isEqualTo(List.of("N3"));
        // Owner 2026-10-19: declining follows the ordinary survey route (self N4 -> target N3), not a full N4 first.
        assertThat(policy.confirmedStages("N4", "N5", "N3", JlptPathPolicy.Choice.DECLINE_RECOMMENDATION))
                .isEqualTo(List.of("N3"));
        assertThat(policy.confirmedStages("N4", "N5", "N3", JlptPathPolicy.Choice.ACCEPT_RECOMMENDATION))
                .isEqualTo(List.of("N5", "N4", "N3"));
        assertThat(policy.confirmedStages("N4", "N5", "N4", JlptPathPolicy.Choice.DECLINE_RECOMMENDATION))
                .isEqualTo(List.of("N4"));
    }

    @Test
    void consentCannotInventAnUnsupportedZeroToN3Route() {
        assertThatThrownBy(() -> policy.confirmedStages("N3", "BELOW_N5", "N3",
                JlptPathPolicy.Choice.ACCEPT_RECOMMENDATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.confirmedStages("N3", "N5", "N2",
                JlptPathPolicy.Choice.DECLINE_RECOMMENDATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitReviewBelowHigherAssessmentCanTraverseSupportedStagesWithoutChangingAssessment() {
        assertThat(policy.confirmedHigherAssessmentReviewStages("N3", "N1", "N3", "N5"))
                .isEqualTo(List.of("N5", "N4", "N3"));
        assertThat(policy.confirmedHigherAssessmentReviewStages("N3", "N1", "N3", "N4"))
                .isEqualTo(List.of("N4", "N3"));
        assertThat(policy.confirmedHigherAssessmentReviewStages("N3", "N1", "N3", "N3"))
                .isEqualTo(List.of("N3"));
    }

    /** The Smart Test result page offers this choice whenever assessed &gt; target, so every survey
     * the target matrix accepts (upgrade, UNKNOWN, from-zero) must be able to complete it. */
    @ParameterizedTest
    @CsvSource({
            "N5,N3,N4,N5,N5|N4",
            "N5,N1,N4,N4,N4",
            "N4,N2,N3,N4,N4|N3",
            "UNKNOWN,N2,N4,N5,N5|N4",
            "UNKNOWN,N3,N5,N5,N5",
            "FROM_ZERO,N3,N4,N5,N5|N4"
    })
    void higherAssessmentThanTargetReviewWorksForEverySurveyTheTargetMatrixAccepts(
            String survey, String assessed, String target, String start, String stages) {
        assertThat(policy.confirmedHigherAssessmentReviewStages(survey, assessed, target, start))
                .isEqualTo(List.of(stages.split("\\|")));
    }

    @Test
    void higherAssessmentReviewIsNotABackdoorAroundLowerOrEqualAssessments() {
        // Assessed below the target: the consent-based lower-assessment flow applies instead.
        assertThatThrownBy(() -> policy.confirmedHigherAssessmentReviewStages("UNKNOWN", "N5", "N3", "N4"))
                .isInstanceOf(IllegalArgumentException.class);
        // Assessed equal to the target: the ordinary same-level route applies.
        assertThatThrownBy(() -> policy.confirmedHigherAssessmentReviewStages("N5", "N4", "N4", "N5"))
                .isInstanceOf(IllegalArgumentException.class);
        // Target N2/N1 stays unpublished.
        assertThatThrownBy(() -> policy.confirmedHigherAssessmentReviewStages("UNKNOWN", "N1", "N2", "N3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void higherAssessmentReviewRejectsUnconfirmedOrUnsupportedStageSelections() {
        assertThatThrownBy(() -> policy.confirmedHigherAssessmentReviewStages("N3", "N1", "N3", "BELOW_N5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.confirmedHigherAssessmentReviewStages("N3", "N1", "N3", "N1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.confirmedHigherAssessmentReviewStages("N3", "N1", "N4", "N5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.confirmedHigherAssessmentReviewStages("N3", "N3", "N3", "N5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Owner 2026-10-19 (Q6): the approved N5–N1 matrix — same level or one adjacent step, never skipping ----

    private final JlptPathPolicy full = JlptPathPolicy.releasing("N5", "N4", "N3", "N2", "N1");

    @ParameterizedTest
    @CsvSource({
            "BELOW_N5,N5,true,N5,FOUNDATION|N5",
            "BELOW_N5,N4,true,N4,FOUNDATION|N5|N4",
            "BELOW_N5,N3,false,N4,",
            "N5,N4,true,N4,N4",
            "N4,N3,true,N3,N3",
            "N3,N3,true,N3,N3",
            "N3,N2,true,N2,N2",
            "N3,N1,false,N2,",
            "N2,N2,true,N2,N2",
            "N2,N1,true,N1,N1",
            "N1,N1,true,N1,N1",
            "N1,N3,false,N1,",
            "N4,N2,false,N3,"
    })
    void approvedMatrixOpensN3ToN2AndN2ToN1WithoutSkippingOrFromZeroBeyondN4(String assessed, String requested,
            boolean allowed, String recommended, String stageText) {
        TargetEligibility result = full.evaluate(assessed, requested);

        assertThat(result.allowed()).isEqualTo(allowed);
        assertThat(result.recommendedTarget()).isEqualTo(recommended);
        assertThat(String.join("|", result.stageLevels())).isEqualTo(stageText == null ? "" : stageText);
    }

    @Test
    void theDefaultReleaseStaysN5ToN3UntilN2AndN1AreReleasedTogetherWithTheirData() {
        assertThat(policy.releasedTargets()).containsExactly("N5", "N4", "N3");
        assertThat(policy.evaluate("N3", "N2").reasonCode()).isEqualTo("PATH_NOT_SUPPORTED");
        assertThat(full.releasedTargets()).containsExactly("N5", "N4", "N3", "N2", "N1");
        assertThat(full.nextTargetAfter("N3")).contains("N2");
        assertThat(full.nextTargetAfter("N2")).contains("N1");
        assertThat(full.nextTargetAfter("N1")).isEmpty();
        assertThatThrownBy(() -> JlptPathPolicy.releasing("N5", "N3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JlptPathPolicy.releasing("N4")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consentAndReviewRoutesWalkAdjacentStagesUpToN1() {
        assertThat(full.confirmedStages("N1", "N3", "N1", JlptPathPolicy.Choice.ACCEPT_RECOMMENDATION))
                .containsExactly("N3", "N2", "N1");
        assertThat(full.confirmedStages("N1", "N3", "N1", JlptPathPolicy.Choice.DECLINE_RECOMMENDATION))
                .containsExactly("N1");
        assertThat(full.confirmedHigherAssessmentReviewStages("N3", "N1", "N2", "N4"))
                .containsExactly("N4", "N3", "N2");
    }
}
