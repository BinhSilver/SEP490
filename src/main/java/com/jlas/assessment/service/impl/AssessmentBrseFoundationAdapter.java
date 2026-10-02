package com.jlas.assessment.service.impl;

import com.jlas.assessment.repository.AssessmentAttemptRepository;
import com.jlas.assessment.repository.SmartTestSessionRepository;
import com.jlas.learning.service.BrseFoundationQualificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps Assessment ownership/status/goal checks outside the Learning slice. */
@Component
@RequiredArgsConstructor
public class AssessmentBrseFoundationAdapter implements BrseFoundationQualificationPort {
    private final AssessmentAttemptRepository attempts;
    private final SmartTestSessionRepository sessions;

    @Override
    @Transactional(readOnly = true)
    public Result verify(Long userId, Long attemptId) {
        if (userId == null || attemptId == null) return Result.rejected("ATTEMPT_NOT_FOUND");
        var attempt = attempts.findByIdAndUserId(attemptId, userId).orElse(null);
        if (attempt == null) return Result.rejected("ATTEMPT_NOT_FOUND");
        if (!"SMART_TEST".equals(attempt.getAttemptType())) return Result.rejected("ATTEMPT_NOT_SMART_TEST");
        if (!"SUBMITTED".equals(attempt.getStatus())) return Result.rejected("ATTEMPT_NOT_SUBMITTED");
        var session = sessions.findById(attemptId).orElse(null);
        if (session == null) return Result.rejected("SESSION_NOT_FOUND");
        if (!"BA".equals(session.getGoal())) return Result.rejected("GOAL_MISMATCH");
        String level = session.getEstimatedOverallLevel() != null
                ? session.getEstimatedOverallLevel() : session.getSelfReportedLevel();
        if (level == null || level.isBlank()) return Result.rejected("ASSESSED_LEVEL_MISSING");
        return new Result(true, null, level, session.getDailyStudyMinutes());
    }
}
