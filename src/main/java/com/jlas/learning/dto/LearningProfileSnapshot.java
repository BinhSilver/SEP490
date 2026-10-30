package com.jlas.learning.dto;

import java.util.List;

// Internal server snapshot; never accept assessment evidence from request JSON.
// Owner 2026-10-25: {@code partial} marks a Smart Test ended before every skill finished; {@code kanaKnown} and
// {@code entryChoice} are the learner's answers to the entry questions (LearningEntryPolicy), and {@code entryStartLevel}
// the first stage those answers fix (no warm-up of mistakes then).
public record LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
        String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
        String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
        List<WeaknessEvidence> weaknesses, List<SkillScore> skillScores,
        String surveyLevel, String overallAbilityBand, String reviewStartLevel, String discrepancyChoice,
        boolean partial, Boolean kanaKnown, String entryChoice, String entryStartLevel) {

    /** A profile without entry answers (every caller before the 2026-10-25 entry questions). */
    public LearningProfileSnapshot(Long userId, Long assessmentAttemptId, Long surveyId,
            String assessmentRevision, boolean surveyCompleted, boolean assessmentFinalized,
            String assessedLevel, String targetLevel, String goalCode, Integer minutesPerDay,
            List<WeaknessEvidence> weaknesses, List<SkillScore> skillScores,
            String surveyLevel, String overallAbilityBand, String reviewStartLevel, String discrepancyChoice) {
        this(userId, assessmentAttemptId, surveyId, assessmentRevision, surveyCompleted, assessmentFinalized,
                assessedLevel, targetLevel, goalCode, minutesPerDay, weaknesses, skillScores, surveyLevel,
                overallAbilityBand, reviewStartLevel, discrepancyChoice, false, null, null, null);
    }

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
                weaknesses, skillScores, surveyLevel, overallAbilityBand, level, discrepancyChoice,
                partial, kanaKnown, entryChoice, entryStartLevel);
    }

    /** The learner's explicit answer (ACCEPT_RECOMMENDATION / DECLINE_RECOMMENDATION) to a lower Smart Test result. */
    public LearningProfileSnapshot withDiscrepancyChoice(String choice) {
        return new LearningProfileSnapshot(userId, assessmentAttemptId, surveyId, assessmentRevision,
                surveyCompleted, assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, surveyLevel, overallAbilityBand, reviewStartLevel, choice,
                partial, kanaKnown, entryChoice, entryStartLevel);
    }

    /** Whether the Smart Test behind this profile ended before every selected skill finished. */
    public LearningProfileSnapshot withPartial(boolean ended) {
        return new LearningProfileSnapshot(userId, assessmentAttemptId, surveyId, assessmentRevision,
                surveyCompleted, assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, surveyLevel, overallAbilityBand, reviewStartLevel, discrepancyChoice,
                ended, kanaKnown, entryChoice, entryStartLevel);
    }

    /** The learner's answers to the entry questions, as given (validated by LearningEntryPolicy). */
    public LearningProfileSnapshot withEntryAnswers(Boolean kana, String choice) {
        return new LearningProfileSnapshot(userId, assessmentAttemptId, surveyId, assessmentRevision,
                surveyCompleted, assessmentFinalized, assessedLevel, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, surveyLevel, overallAbilityBand, reviewStartLevel, discrepancyChoice,
                partial, kana, choice, entryStartLevel);
    }

    /** The profile the path is built from once the entry answers are applied: the level the route starts from, its
     * band, and the first stage those answers fix (null keeps the ordinary start rules). */
    public LearningProfileSnapshot withEntryRoute(String level, String band, String startLevel) {
        return new LearningProfileSnapshot(userId, assessmentAttemptId, surveyId, assessmentRevision,
                surveyCompleted, assessmentFinalized, level, targetLevel, goalCode, minutesPerDay,
                weaknesses, skillScores, surveyLevel, band, reviewStartLevel, discrepancyChoice,
                partial, kanaKnown, entryChoice, startLevel);
    }
}
