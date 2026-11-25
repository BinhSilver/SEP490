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

    /** Whether the attempt has its Smart Test session row (finishing an attempt without one would 404). */
    @Transactional(readOnly = true)
    public boolean hasSession(Long attemptId) {
        return sessions.existsById(attemptId);
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
                score(skills, "GRAMMAR"), result.partial(), modeScore(skills, "VOCABULARY"),
                modeScore(skills, "KANJI"), modeScore(skills, "GRAMMAR"), score(skills, "READING"),
                score(skills, "LISTENING"));
    }

    private static final List<String> LEVEL_ORDER = List.of("N5", "N4", "N3", "N2", "N1");

    /**
     * E2E-005: the combined/split suggestion compares skills on one scale. A skill one level higher counts 100 points
     * more; mastery (% right inside the questions of that skill) only separates skills at the same level. Vocabulary,
     * kanji and grammar all at the same level and band score the same, so they are never "far apart".
     */
    static Integer modeScore(List<SmartTestResponse.SkillResultView> skills, String skill) {
        Integer mastery = score(skills, skill);
        SmartTestResponse.SkillResultView view = skills.stream().filter(s -> skill.equals(s.skill()))
                .findFirst().orElse(null);
        if (mastery == null || view == null) {
            return mastery;
        }
        int level = LEVEL_ORDER.indexOf(view.estimatedLevel());
        if (level < 0) {
            return mastery;
        }
        boolean sameTier = skills.stream()
                .filter(s -> MODE_SKILLS.contains(s.skill()) && s.masteryScore() != null)
                .allMatch(s -> java.util.Objects.equals(s.estimatedLevel(), view.estimatedLevel())
                        && java.util.Objects.equals(s.abilityBand(), view.abilityBand()));
        return level * 100 + (sameTier ? 0 : mastery);
    }

    private static final List<String> MODE_SKILLS = List.of("VOCABULARY", "KANJI", "GRAMMAR");

    private static Integer score(List<SmartTestResponse.SkillResultView> skills, String skill) {
        return skills.stream()
                .filter(s -> skill.equals(s.skill()) && s.masteryScore() != null)
                .findFirst()
                .map(SmartTestResponse.SkillResultView::masteryScore)
                .map(v -> v.setScale(0, RoundingMode.HALF_UP).intValueExact())
                .orElse(null);
    }
}
