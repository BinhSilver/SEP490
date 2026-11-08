package com.jlas.mission.service;

import com.jlas.mission.dto.FreezeActionResponse;
import com.jlas.mission.dto.StreakResponse;
import java.time.LocalDate;

/**
 * Daily-learning streak in the learner's timezone.
 *
 * <p>A missed day is not forgiven automatically: the learner gets a notification and can protect
 * the missed day with a freeze (owned or bought) until the end of the following day.
 */
public interface StreakService {

    /** Counts {@code day} as a completed day; joins the caller's transaction. Late or repeated days are ignored. */
    void recordCompletion(Long userId, LocalDate day);

    /** Current state; opens a freeze offer or ends the streak when a missed day is found. */
    StreakResponse refresh(Long userId);

    FreezeActionResponse useFreeze(Long userId, boolean purchase);

    FreezeActionResponse declineFreeze(Long userId);

    /** Finds learners who missed yesterday and sends the freeze offer. Returns how many were checked. */
    int remindAtRiskStreaks(int limit);
}
