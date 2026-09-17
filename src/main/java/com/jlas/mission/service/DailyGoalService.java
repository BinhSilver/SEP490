package com.jlas.mission.service;

import com.jlas.mission.dto.DailyGoalProgress;

/** Records daily learning activity and completes the daily goal when all targets are met. */
public interface DailyGoalService {

    void recordVocabulary(Long userId, int count);

    void recordQuiz(Long userId);

    void recordStudyMinutes(Long userId, int minutes);

    /** Returns today's progress against the learner's targets. */
    DailyGoalProgress summary(Long userId);
}
