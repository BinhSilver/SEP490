package com.jlas.jlptpath.service.impl;

import com.jlas.assessment.dto.SmartTestResponse;
import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.repository.AssessmentAttemptRepository;
import com.jlas.assessment.repository.SmartTestSessionRepository;
import com.jlas.assessment.service.AssessmentService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSmartTestSnapshot;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Read-only view of the learner's Smart Test attempts for the JLPT path. */
@Component
public class JpathSmartTestReader {

    static final String SMART_TEST = "SMART_TEST";
    static final String IN_PROGRESS = "IN_PROGRESS";
    static final String SUBMITTED = "SUBMITTED";

    private final AssessmentAttemptRepository attempts;
    private final SmartTestSessionRepository sessions;
    private final AssessmentService assessment;

    public JpathSmartTestReader(AssessmentAttemptRepository attempts, SmartTestSessionRepository sessions,
                                AssessmentService assessment) {
        this.attempts = attempts;
        this.sessions = sessions;
        this.assessment = assessment;
    }

    /** The newer (larger id) of the newest IN_PROGRESS and the newest SUBMITTED Smart Test attempt. */
    @Transactional(readOnly = true)
    public Optional<AssessmentAttempt> latestAttempt(Long userId) {
        return Stream.of(IN_PROGRESS, SUBMITTED)
                .map(status -> attempts.findFirstByUserIdAndAttemptTypeAndStatusOrderBySubmittedAtDescIdDesc(
                        userId, SMART_TEST, status))
                .flatMap(Optional::stream)
                .max((a, b) -> Long.compare(a.getId(), b.getId()));
    }

    /**
     * Snapshot of {@link #latestAttempt}; an IN_PROGRESS attempt does not load the result, and a SUBMITTED attempt
     * without its Smart Test session row comes back without goal, level or scores.
     */
    @Transactional(readOnly = true)
    public Optional<JpathSmartTestSnapshot> latest(Long userId) {
        return latestAttempt(userId).map(attempt -> snapshot(userId, attempt));
    }

    /** Snapshot of the learner's own Smart Test attempt; NOT_FOUND for another user's or a non-Smart-Test attempt. */
    @Transactional(readOnly = true)
    public JpathSmartTestSnapshot read(Long userId, Long attemptId) {
        AssessmentAttempt attempt = attempts.findByIdAndUserId(attemptId, userId)
                .filter(a -> SMART_TEST.equals(a.getAttemptType()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy bài Smart Test."));
        return snapshot(userId, attempt);
    }

    private JpathSmartTestSnapshot snapshot(Long userId, AssessmentAttempt attempt) {
        if (IN_PROGRESS.equals(attempt.getStatus())) {
            return new JpathSmartTestSnapshot(attempt.getId(), attempt.getStatus(), null, null, null, null, null, null,
                    false);
        }
        if (!sessions.existsById(attempt.getId())) {
            // A submitted attempt without its smart_test_session row has no result to read. Checked up front:
            // getSmartTest's 404 would mark the surrounding transaction rollback-only.
            return new JpathSmartTestSnapshot(attempt.getId(), attempt.getStatus(), null, null, null, null, null,
                    null, false);
        }
        SmartTestResponse response = assessment.getSmartTest(userId, attempt.getId());
        SmartTestResponse.ResultView result = response.result();
        if (result == null) {
            return new JpathSmartTestSnapshot(attempt.getId(), attempt.getStatus(), response.goal(), null, null, null,
                    null, null, false);
        }
        List<SmartTestResponse.SkillResultView> skills = result.skills() == null ? List.of() : result.skills();
        return new JpathSmartTestSnapshot(attempt.getId(), attempt.getStatus(), response.goal(), result.abilityBand(),
                result.estimatedOverallLevel(), score(skills, "VOCABULARY"), score(skills, "KANJI"),
                score(skills, "GRAMMAR"), result.partial());
    }

    private static Integer score(List<SmartTestResponse.SkillResultView> skills, String skill) {
        return skills.stream()
                .filter(s -> skill.equals(s.skill()) && s.masteryScore() != null)
                .findFirst()
                .map(SmartTestResponse.SkillResultView::masteryScore)
                .map(v -> v.setScale(0, RoundingMode.HALF_UP).intValueExact())
                .orElse(null);
    }
}
