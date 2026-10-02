package com.jlas.common.learning;

import static org.assertj.core.api.Assertions.assertThat;

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
            "N3,N2,true,N2,N2",
            "N3,N1,false,N2,",
            "N2,N1,true,N1,N1"
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
    void recommendsTheImmediateNextLevelAfterN3AndN2() {
        assertThat(policy.nextTargetAfter("N3")).contains("N2");
        assertThat(policy.nextTargetAfter("N2")).contains("N1");
    }

    @Test
    void n1HasNoHigherJlptTarget() {
        assertThat(policy.nextTargetAfter("N1")).isEmpty();
    }
}
