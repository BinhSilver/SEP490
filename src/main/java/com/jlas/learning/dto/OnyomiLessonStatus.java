package com.jlas.learning.dto;

/** Where a learner stands on one Onyomi lesson. */
public enum OnyomiLessonStatus {
    /** The previous lesson (or the previous stage's final test) is not passed yet. */
    LOCKED,
    /** Unlocked but not opened yet. */
    AVAILABLE,
    /** Opened, practice quiz not passed yet. */
    IN_PROGRESS,
    /** Practice quiz passed. */
    COMPLETED
}
