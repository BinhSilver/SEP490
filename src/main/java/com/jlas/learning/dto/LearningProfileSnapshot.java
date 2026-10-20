package com.jlas.learning.dto;

import java.util.List;

// Internal server snapshot; never accept assessment evidence from request JSON.
public record LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
        String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
        String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
        List<WeaknessEvidence> weaknesses, List<SkillScore> skillScores,
        String surveyLevel, String overallAbilityBand) {

    /** Legacy callers have no survey/overall-band snapshot; retain their selection behavior. */
    public LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
            String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
            String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
            List<WeaknessEvidence> weaknesses, List<SkillScore> skillScores) {
        this(userId, assessmentAttemptId, surveyId, assessmentRevision, surveyCompleted,
                assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, null, null);
    }

    /** [JLAS-W0108] Tương thích ngược: snapshot không có điểm số skill (test/adapters) mặc định rỗng. */
    public LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
            String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
            String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
            List<WeaknessEvidence> weaknesses) {
        this(userId, assessmentAttemptId, surveyId, assessmentRevision, surveyCompleted,
                assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, List.of());
    }
}
