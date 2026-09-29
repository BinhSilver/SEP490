package com.jlas.assessment.service.impl;

import com.jlas.assessment.service.impl.SmartTestAdaptivePolicy.BatchScore;
import com.jlas.assessment.service.impl.SmartTestAdaptivePolicy.Decision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmartTestAdaptivePolicyTest {

    private final SmartTestAdaptivePolicy policy = new SmartTestAdaptivePolicy();

    @Test
    void threeOfFiveRequiresAnotherBatchAtTheSameLevel() {
        Decision decision = policy.decide(List.of(new BatchScore(1, "N3", 3)));
        assertThat(decision.finished()).isFalse();
        assertThat(decision.nextLevel()).isEqualTo("N3");
    }

    @Test
    void confirmationBatchWithTwoOrThreeCorrectDeterminesCurrentLevel() {
        for (int correct : List.of(2, 3)) {
            Decision decision = policy.decide(List.of(
                    new BatchScore(1, "N3", 3), new BatchScore(2, "N3", correct)));
            assertThat(decision.finished()).isTrue();
            assertThat(decision.estimatedLevel()).isEqualTo("N3");
        }
    }

    @Test
    void confirmationBatchWithZeroOrOneCorrectMovesDown() {
        for (int correct : List.of(0, 1)) {
            Decision decision = policy.decide(List.of(
                    new BatchScore(1, "N3", 3), new BatchScore(2, "N3", correct)));
            assertThat(decision.finished()).isFalse();
            assertThat(decision.nextLevel()).isEqualTo("N4");
        }
    }

    @Test
    void confirmationBatchWithFourOrFiveCorrectMovesUp() {
        for (int correct : List.of(4, 5)) {
            Decision decision = policy.decide(List.of(
                    new BatchScore(1, "N3", 3), new BatchScore(2, "N3", correct)));
            assertThat(decision.finished()).isFalse();
            assertThat(decision.nextLevel()).isEqualTo("N2");
        }
    }

    @Test
    void oppositeResultsDetermineStableAbilityRange() {
        Decision decision = policy.decide(List.of(
                new BatchScore(1, "N3", 5), new BatchScore(2, "N2", 1)));
        assertThat(decision.finished()).isTrue();
        assertThat(decision.estimatedLevel()).isEqualTo("N3");
    }

    @Test
    void perfectN5ThenFailedN4FallsBackToProvenN5() {
        Decision decision = policy.decide(List.of(
                new BatchScore(1, "N5", 5), new BatchScore(2, "N4", 0)));
        assertThat(decision.finished()).isTrue();
        assertThat(decision.estimatedLevel()).isEqualTo("N5");
    }

    @Test
    void n3ConfirmationThenN2ProbeAlwaysFinishesAtN3OrN2() {
        for (int correct : List.of(0, 1, 2)) {
            Decision decision = policy.decide(List.of(
                    new BatchScore(1, "N3", 3),
                    new BatchScore(2, "N3", 5),
                    new BatchScore(3, "N2", correct)));
            assertThat(decision.finished()).isTrue();
            assertThat(decision.estimatedLevel()).isEqualTo("N3");
        }

        Decision threeCorrect = policy.decide(List.of(
                new BatchScore(1, "N3", 3),
                new BatchScore(2, "N3", 5),
                new BatchScore(3, "N2", 3)));
        assertThat(threeCorrect.finished()).isTrue();
        assertThat(threeCorrect.estimatedLevel()).isEqualTo("N3");

        for (int correct : List.of(4, 5)) {
            Decision decision = policy.decide(List.of(
                    new BatchScore(1, "N3", 3),
                    new BatchScore(2, "N3", 5),
                    new BatchScore(3, "N2", correct)));
            assertThat(decision.finished()).isTrue();
            assertThat(decision.estimatedLevel()).isEqualTo("N2");
        }
    }

    @Test
    void n3ConfirmationThenN4ProbeNeverAwardsUntestedN5() {
        for (int correct : List.of(0, 1, 2)) {
            Decision decision = policy.decide(List.of(
                    new BatchScore(1, "N3", 3),
                    new BatchScore(2, "N3", 1),
                    new BatchScore(3, "N4", correct)));
            assertThat(decision.finished()).isTrue();
            assertThat(decision.estimatedLevel()).isEqualTo("N4");
        }

        Decision sameLevel = policy.decide(List.of(
                new BatchScore(1, "N3", 3),
                new BatchScore(2, "N3", 1),
                new BatchScore(3, "N4", 3)));
        assertThat(sameLevel.finished()).isTrue();
        assertThat(sameLevel.estimatedLevel()).isEqualTo("N4");

        for (int correct : List.of(4, 5)) {
            Decision decision = policy.decide(List.of(
                    new BatchScore(1, "N3", 3),
                    new BatchScore(2, "N3", 1),
                    new BatchScore(3, "N4", correct)));
            assertThat(decision.finished()).isTrue();
            assertThat(decision.estimatedLevel()).isEqualTo("N4");
        }
    }

    @Test
    void questionLimitNeverAwardsAnUntestedTargetLevel() {
        Decision decision = policy.decide(List.of(
                new BatchScore(1, "N5", 4),
                new BatchScore(2, "N4", 4),
                new BatchScore(3, "N3", 4),
                new BatchScore(4, "N2", 3),
                new BatchScore(5, "N2", 5)));
        assertThat(decision.finished()).isTrue();
        assertThat(decision.estimatedLevel()).isEqualTo("N2");
    }

    @Test
    void levelNeverMovesOutsideJlptBoundaries() {
        assertThat(policy.decide(List.of(new BatchScore(1, "N5", 0))).estimatedLevel()).isEqualTo("N5");
        assertThat(policy.decide(List.of(new BatchScore(1, "N1", 5))).estimatedLevel()).isEqualTo("N1");
    }
}
