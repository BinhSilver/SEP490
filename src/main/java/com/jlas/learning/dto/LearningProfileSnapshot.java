package com.jlas.learning.dto;

import java.util.List;

// Internal server snapshot; never accept assessment evidence from request JSON.
public record LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
        String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
        String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
        List<WeaknessEvidence> weaknesses, List<SkillScore> skillScores,
        String surveyLevel, String overallAbilityBand, String reviewStartLevel, String discrepancyChoice) {

    /** A confirmed review start below a higher result, without a lower-result choice. */
    public LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
            String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
            String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
            List<WeaknessEvidence> weaknesses, List<SkillScore> skillScores,
            String surveyLevel, String overallAbilityBand, String reviewStartLevel) {
        this(userId, assessmentAttemptId, surveyId, assessmentRevision, surveyCompleted,
                assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, surveyLevel, overallAbilityBand, reviewStartLevel, null);
    }

    /** Existing profile sources do not opt into a review route. */
    public LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
            String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
            String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
            List<WeaknessEvidence> weaknesses, List<SkillScore> skillScores,
            String surveyLevel, String overallAbilityBand) {
        this(userId, assessmentAttemptId, surveyId, assessmentRevision, surveyCompleted,
                assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, surveyLevel, overallAbilityBand, null, null);
    }

    /** Legacy callers have no survey/overall-band snapshot; retain their selection behavior. */
    public LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
            String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
            String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
            List<WeaknessEvidence> weaknesses, List<SkillScore> skillScores) {
        this(userId, assessmentAttemptId, surveyId, assessmentRevision, surveyCompleted,
                assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, null, null, null, null);
    }

    /** [JLAS-W0108] Tương thích ngược: snapshot không có điểm số skill (test/adapters) mặc định rỗng. */
    public LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
            String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
            String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
            List<WeaknessEvidence> weaknesses) {
        this(userId, assessmentAttemptId, surveyId, assessmentRevision, surveyCompleted,
                assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, List.of(), null, null, null, null);
    }

    public LearningProfileSnapshot withReviewStartLevel(String level) {
        return new LearningProfileSnapshot(userId, assessmentAttemptId, surveyId, assessmentRevision,
                surveyCompleted, assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, surveyLevel, overallAbilityBand, level, discrepancyChoice);
    }

    /** The learner's explicit answer (ACCEPT_RECOMMENDATION / DECLINE_RECOMMENDATION) to a lower Smart Test result. */
    public LearningProfileSnapshot withDiscrepancyChoice(String choice) {
        return new LearningProfileSnapshot(userId, assessmentAttemptId, surveyId, assessmentRevision,
                surveyCompleted, assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, surveyLevel, overallAbilityBand, reviewStartLevel, choice);
    }
}
