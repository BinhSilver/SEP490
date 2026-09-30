package com.jlas.assessment.service.impl;

import com.jlas.assessment.entity.JlptSectionResult;
import com.jlas.assessment.repository.JlptExamAttemptRepository;
import com.jlas.learning.service.JlptQualificationPort;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Task 9 — Assessment-owned adapter for Learning's {@link JlptQualificationPort}: qualification is decided from stored Assessment rows only. Another user's attempt is
 * reported as ATTEMPT_NOT_FOUND so the endpoint cannot be used to probe attempt ids. */
@Component
public class AssessmentJlptQualificationAdapter implements JlptQualificationPort {
    private final JlptExamAttemptRepository attempts;
    private final java.util.Set<String> acceptedPolicyVersions;

    /** {@code acceptedPolicyVersions} comes from server configuration only ({@code assessment.jlpt.accepted-policy-versions});
     * an empty or blank list accepts nothing (fail closed). */
    public AssessmentJlptQualificationAdapter(JlptExamAttemptRepository attempts,
            @org.springframework.beans.factory.annotation.Value("${assessment.jlpt.accepted-policy-versions:}")
            java.util.List<String> acceptedPolicyVersions) {
        this.attempts = attempts;
        this.acceptedPolicyVersions = (acceptedPolicyVersions == null ? java.util.List.<String>of() : acceptedPolicyVersions)
                .stream().filter(v -> v != null && !v.isBlank()).map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Result verify(Long userId, Long attemptId, String targetLevel) {
        if (userId == null || attemptId == null || targetLevel == null) return fail("ATTEMPT_NOT_FOUND");
        var exam = attempts.findDetailedById(attemptId).orElse(null);
        if (exam == null || exam.getAttempt().getUser() == null
                || !userId.equals(exam.getAttempt().getUser().getId())) {
            return fail("ATTEMPT_NOT_FOUND");
        }
        if (!"JLPT".equals(exam.getAttempt().getAttemptType())) return fail("ATTEMPT_NOT_JLPT");
        if (!"SUBMITTED".equals(exam.getAttempt().getStatus())) return fail("ATTEMPT_NOT_SUBMITTED");
        if (!targetLevel.equals(exam.getExam().getJlptLevel())) return fail("LEVEL_MISMATCH");
        String snapshot = exam.getPolicyVersion();
        if (snapshot == null || snapshot.isBlank()) return fail("POLICY_VERSION_MISSING");
        // The attempt's own immutable snapshot is judged against the configured allow-list, not the exam's current policy.
        if (!acceptedPolicyVersions.contains(snapshot.trim())) return fail("POLICY_VERSION_UNSUPPORTED");
        var required = attempts.findExamSections(exam.getExam().getId());
        if (required.isEmpty()) return fail("EXAM_HAS_NO_SECTIONS");
        var sections = attempts.findSectionResults(attemptId);
        if (sections.isEmpty()) return fail("NO_SECTION_RESULTS");
        var answered = sections.stream().map(r -> r.getSection().getId()).collect(java.util.stream.Collectors.toSet());
        if (required.stream().anyMatch(section -> !answered.contains(section.getId()))) {
            return fail("SECTION_RESULT_MISSING");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (JlptSectionResult section : sections) {
            total = total.add(section.getScore());
            if (section.getScore().compareTo(section.getSection().getMinPassingScore()) < 0) {
                return fail("SECTION_BELOW_PASSING");
            }
        }
        if (total.compareTo(exam.getExam().getPassingScore()) < 0) return fail("TOTAL_SCORE_BELOW_PASSING");
        return new Result(true, null);
    }

    private static Result fail(String reason) {
        return new Result(false, reason);
    }
}
