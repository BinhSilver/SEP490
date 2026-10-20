package com.jlas.learning.service;

import com.jlas.learning.entity.UserLearningPath;
import java.util.Set;

/** Shared behavior boundary for survey-only communication paths. */
public final class GoalPathPolicy {
    private static final Set<String> COMMUNICATION_GOALS = Set.of("BA", "TRAVEL", "DAILY_LIFE");

    private GoalPathPolicy() { }

    public static boolean isCommunicationGoal(UserLearningPath path) {
        return path != null && path.getGoal() != null && COMMUNICATION_GOALS.contains(path.getGoal());
    }
}
