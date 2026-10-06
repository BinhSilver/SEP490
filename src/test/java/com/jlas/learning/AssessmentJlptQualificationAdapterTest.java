package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.jlas.assessment.service.impl.AssessmentJlptQualificationAdapter;
import org.junit.jupiter.api.Test;

/** [JLAS-W0108] Integration with the real JLPT module on develop (merged 2026-10-06). That module serves exams and
 * practice questions only: it grades in the browser and stores no attempt, owner, policy snapshot or section
 * result (jlpt_exam_attempt / jlpt_exam_section / jlpt_section_result were removed). The Mock Test contract
 * (docs/superpowers/contracts/2026-09-30-mock-test-integration-contract.md §2) therefore cannot be verified, so
 * the adapter must fail closed for every input — a JLPT path is never completed from client-side scoring. */
class AssessmentJlptQualificationAdapterTest {
    private final AssessmentJlptQualificationAdapter adapter = new AssessmentJlptQualificationAdapter();

    @Test
    void neverQualifiesWithoutAServerRecordedAttempt() {
        for (Long attemptId : new Long[] {1L, 500L, Long.MAX_VALUE}) {
            for (String level : new String[] {"N5", "N4", "N3", "N2", "N1"}) {
                var result = adapter.verify(7L, attemptId, level);
                assertFalse(result.qualified(), "attempt " + attemptId + " at " + level + " must not qualify");
                assertEquals("MOCK_TEST_RESULT_STORE_UNAVAILABLE", result.reasonCode());
            }
        }
    }

    @Test
    void missingInputIsStillReportedAsAttemptNotFound() {
        assertEquals("ATTEMPT_NOT_FOUND", adapter.verify(null, 1L, "N4").reasonCode());
        assertEquals("ATTEMPT_NOT_FOUND", adapter.verify(7L, null, "N4").reasonCode());
        assertEquals("ATTEMPT_NOT_FOUND", adapter.verify(7L, 1L, null).reasonCode());
    }
}
