package com.jlas.mission.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/** Read-only seam from retention into the learner's current study plan. */
public interface DailyGoalPlanPort {
    record Targets(int vocabularyTarget, int quizTarget, int minutesTarget, String source) { }

    /**
     * [JLAS-W0108] F128: what the learner did on their study paths on one day, in the daily goal's units (new items,
     * quizzes, minutes). It is added to the counters the learning events wrote, never stored.
     */
    record Activity(int vocabulary, int quizzes, int minutes) {
        public static final Activity NONE = new Activity(0, 0, 0);
    }

    Optional<Targets> resolve(Long userId);

    /** Study on the learner's paths during {@code day} of {@code zone}; nothing known → {@link Activity#NONE}. */
    default Activity activity(Long userId, LocalDate day, ZoneId zone) {
        return Activity.NONE;
    }
}
