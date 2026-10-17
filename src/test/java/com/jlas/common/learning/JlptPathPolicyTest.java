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
        assertThat(policy.confirmedStages("N4", "N5", "N3", JlptPathPolicy.Choice.DECLINE_RECOMMENDATION))
                .isEqualTo(List.of("N4", "N3"));
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
}
