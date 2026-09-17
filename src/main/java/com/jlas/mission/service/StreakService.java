package com.jlas.mission.service;

import com.jlas.mission.dto.StreakSnapshot;

/** Maintains the learner's daily-learning streak across day boundaries. */
public interface StreakService {

    /**
     * Registers one active learning day. Repeated calls on the same day are a no-op; a skipped
     * day resets the streak to one. Returns the current streak length.
     */
    int recordCompletion(Long userId);

    /** Returns the current streak state (zeros when the user has no streak row yet). */
    StreakSnapshot snapshot(Long userId);
}
