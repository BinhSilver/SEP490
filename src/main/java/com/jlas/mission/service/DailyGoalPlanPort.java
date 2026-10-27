package com.jlas.mission.service;

import java.util.Optional;

/** Read-only seam from retention into the learner's current study plan. */
public interface DailyGoalPlanPort {
    record Targets(int vocabularyTarget, int quizTarget, int minutesTarget, String source) { }
    Optional<Targets> resolve(Long userId);
}
