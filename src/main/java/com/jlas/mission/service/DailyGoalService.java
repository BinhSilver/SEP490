package com.jlas.mission.service;

import com.jlas.mission.dto.DailyGoalProgress;
import com.jlas.mission.dto.LearningTargetsRequest;
import java.time.LocalDate;

/** Daily goal: activity totals per learner-day and the one-time reward when all targets are met. */
public interface DailyGoalService {

    /** Adds activity to {@code day}; joins the caller's transaction. Completing the goal queues the reward. */
    void addActivity(Long userId, LocalDate day, int vocabulary, int quizzes, int minutes);

    DailyGoalProgress progress(Long userId, LocalDate day);

    /** Saves new targets and re-checks today, since lower targets can complete the goal. */
    DailyGoalProgress updateTargets(Long userId, LearningTargetsRequest request);

    /**
     * [JLAS-W0108] F128: re-checks {@code day} with the learner's path activity in its own transaction; completing the
     * goal queues the reward and records the streak once. True only when this call completed the goal. Writes
     * nothing while the targets are not met.
     */
    boolean refresh(Long userId, LocalDate day);

    /** {@link #refresh} for the learner's today. */
    boolean refreshToday(Long userId);
}
