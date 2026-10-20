package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.jlas.learning.dto.FinalEligibilityResponse;
import com.jlas.learning.service.impl.LearningFinalEligibilityServiceImpl;
import com.jlas.learning.service.impl.LearningFinalEligibilityServiceImpl.StageCount;
import java.util.List;
import org.junit.jupiter.api.Test;

/** [JLAS-W0108] Task 9 — final (Mock Test) eligibility from required-package counts. Pure logic: fixtures
 * below are tiny count tuples for code tests only, not curriculum data. */
class LearningFinalEligibilityServiceTest {

    @Test void communicationStageDoesNotOpenJlptMockAtFiftyPercent() {
        var result = LearningFinalEligibilityServiceImpl.evaluate(List.of(stage(1, 0, "BASIC", 5, 10)));
        assertFalse(result.eligible());
        assertEquals("NOT_APPLICABLE", result.reasonCode());
        assertEquals(50, result.stages().getFirst().percent());
    }

    private static StageCount stage(long id, int index, String level, int completed, int total) {
        return new StageCount(id, index, level, "ACTIVE", completed, total);
    }

    private FinalEligibilityResponse evaluate(int priorDone, int priorTotal, int targetDone, int targetTotal) {
        return LearningFinalEligibilityServiceImpl.evaluate(List.of(stage(1, 0, "N5", priorDone, priorTotal),
                stage(2, 1, "N4", targetDone, targetTotal)));
    }

    @Test void targetStageAt49PercentIsNotEligible() {
        var result = evaluate(100, 100, 49, 100);
        assertFalse(result.eligible());
        assertEquals("TARGET_STAGE_BELOW_THRESHOLD", result.reasonCode());
    }

    @Test void targetStageAtExactly50PercentIsEligible() {
        var result = evaluate(100, 100, 50, 100);
        assertTrue(result.eligible());
        assertNull(result.reasonCode());
    }

    @Test void priorStageAt99OfHundredBlocksEvenWhenTargetIsComplete() {
        var result = evaluate(99, 100, 100, 100);
        assertFalse(result.eligible());
        assertEquals("PRIOR_STAGE_INCOMPLETE", result.reasonCode());
    }

    @Test void percentIsFlooredNotRounded() {
        // 7/15 = 46.67% -> 46, and 1/3 = 33; 2/4 = 50 exactly.
        assertEquals(46, evaluate(1, 1, 7, 15).stages().get(1).percent());
        assertEquals(33, evaluate(1, 1, 1, 3).stages().get(1).percent());
        assertTrue(evaluate(1, 1, 2, 4).eligible());
        // 99.9% must never round up to 100.
        assertEquals(99, evaluate(1, 1, 999, 1000).stages().get(1).percent());
    }

    @Test void stageWithNoRequiredPackagesIsBlockedNeverTreatedAsComplete() {
        var emptyTarget = evaluate(10, 10, 0, 0);
        assertFalse(emptyTarget.eligible());
        assertEquals("BLOCKED_CONTENT", emptyTarget.reasonCode());
        assertTrue(emptyTarget.stages().get(1).blocked());

        var emptyPrior = evaluate(0, 0, 10, 10);
        assertFalse(emptyPrior.eligible());
        assertEquals("BLOCKED_CONTENT", emptyPrior.reasonCode());
    }

    @Test void pathWithNoStagesIsBlocked() {
        var result = LearningFinalEligibilityServiceImpl.evaluate(List.of());
        assertFalse(result.eligible());
        assertEquals("BLOCKED_CONTENT", result.reasonCode());
    }

    @Test void singleStagePathUsesItselfAsTargetWithNoPriorStages() {
        assertTrue(LearningFinalEligibilityServiceImpl.evaluate(List.of(stage(1, 0, "N4", 5, 10))).eligible());
        assertFalse(LearningFinalEligibilityServiceImpl.evaluate(List.of(stage(1, 0, "N4", 4, 10))).eligible());
    }

    @Test void threeStageFoundationN5N4RequiresBothPriorStagesComplete() {
        var foundation = stage(1, 0, "FOUNDATION", 10, 10);
        var n5 = stage(2, 1, "N5", 24, 25);
        var n4 = stage(3, 2, "N4", 25, 25);
        assertFalse(LearningFinalEligibilityServiceImpl.evaluate(List.of(foundation, n5, n4)).eligible());
        assertTrue(LearningFinalEligibilityServiceImpl.evaluate(List.of(foundation, stage(2, 1, "N5", 25, 25), n4)).eligible());
    }

    @Test void stagesAreReportedInStageIndexOrderRegardlessOfInputOrder() {
        var result = LearningFinalEligibilityServiceImpl.evaluate(List.of(stage(2, 1, "N4", 50, 100), stage(1, 0, "N5", 100, 100)));
        assertEquals("N5", result.stages().get(0).level());
        assertEquals("N4", result.targetLevel());
        assertTrue(result.eligible());
    }
}
