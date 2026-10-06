package com.jlas.assessment.service.impl;

import com.jlas.learning.service.JlptQualificationPort;
import org.springframework.stereotype.Component;

/** [JLAS-W0108] Assessment-owned adapter for Learning's {@link JlptQualificationPort}.
 *
 * <p>The JLPT module merged from develop on 2026-10-06 grades exams in the browser and persists no attempt,
 * owner, status, policy snapshot or section result (it removed {@code jlpt_exam_attempt},
 * {@code jlpt_exam_section} and {@code jlpt_section_result}). Nothing on the server can prove that a learner passed,
 * so this adapter fails closed for every attempt with {@code MOCK_TEST_RESULT_STORE_UNAVAILABLE}: a JLPT path is
 * never completed from client-reported scores. When the Mock Test owner adds a server-recorded attempt that meets
 * the contract (docs/superpowers/contracts/2026-09-30-mock-test-integration-contract.md §2), restore the
 * verification that lived here at commit 11edab3 against the new tables and add the contract test of §6. */
@Component
public class AssessmentJlptQualificationAdapter implements JlptQualificationPort {
    static final String STORE_UNAVAILABLE = "MOCK_TEST_RESULT_STORE_UNAVAILABLE";

    @Override
    public Result verify(Long userId, Long attemptId, String targetLevel) {
        if (userId == null || attemptId == null || targetLevel == null) return new Result(false, "ATTEMPT_NOT_FOUND");
        return new Result(false, STORE_UNAVAILABLE);
    }
}
