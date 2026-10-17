package com.jlas.learning.service;

import java.util.Locale;
import java.util.Set;

/**
 * Server-owned entry policy for a learning goal.
 * Survey choices express intent only; they are never evidence of demonstrated competency.
 */
public record GoalEntryPolicy(
        boolean surveyRequired,
        AssessmentRequirement assessmentRequirement,
        boolean competencySelfReportIsEvidence,
        Set<String> allowedLearningModes
) {
    private static final Set<String> GOAL_MODES = Set.of(
            "FOUNDATION_FIRST", "COMMUNICATION_FIRST", "BALANCED");
    private static final Set<String> COMMUNICATION_GOALS = Set.of("BA", "TRAVEL", "DAILY_LIFE");

    public enum AssessmentRequirement {
        NONE,
        JLPT_PLACEMENT
    }

    public static GoalEntryPolicy forGoal(String rawGoal) {
        if (rawGoal == null || rawGoal.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        return switch (rawGoal.trim().toUpperCase(Locale.ROOT)) {
            case "JLPT" -> new GoalEntryPolicy(true, AssessmentRequirement.JLPT_PLACEMENT,
                    false, Set.of());
            case "BA" -> new GoalEntryPolicy(true, AssessmentRequirement.NONE,
                    false, GOAL_MODES);
            case "TRAVEL", "DAILY_LIFE" -> new GoalEntryPolicy(true, AssessmentRequirement.NONE,
                    false, GOAL_MODES);
            case "FROM_ZERO" -> new GoalEntryPolicy(true, AssessmentRequirement.NONE,
                    false, Set.of("FOUNDATION_FIRST"));
            default -> throw new IllegalArgumentException("Unsupported learning goal: " + rawGoal);
        };
    }

    /** Goals whose completion is based on approved learning packages, never a JLPT Mock Test. */
    public static boolean isCommunicationGoal(String rawGoal) {
        return rawGoal != null && COMMUNICATION_GOALS.contains(rawGoal.trim().toUpperCase(Locale.ROOT));
    }
}
