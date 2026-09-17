package com.jlas.mission.service;

import com.jlas.common.event.LearningActivityCompleted;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Bridges learning-activity events into daily-goal progress. */
@Component
@RequiredArgsConstructor
public class LearningActivityListener {

    private final DailyGoalService dailyGoal;

    @EventListener
    public void onLearningActivity(LearningActivityCompleted event) {
        String type = event.activityType();
        if ("COMPLETE_LESSON".equals(type)) {
            dailyGoal.recordStudyMinutes(event.userId(), Math.max(0, event.minutes()));
        } else if ("COMPLETE_QUIZ".equals(type)) {
            dailyGoal.recordQuiz(event.userId());
        } else if ("PUBLISH_SHADOWING".equals(type)) {
            dailyGoal.recordVocabulary(event.userId(), 1);
        }
    }
}
