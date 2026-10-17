package com.jlas.learning.enums;

/** [JLAS-W0108] Task 10 - how a learner is opening an activity. LEARN is the only mode that can advance the
 * path; REVIEW is read-only; PRACTICE records a replay but never completes, rewards, unlocks or demotes. */
public enum StudyMode {
    LEARN,
    REVIEW,
    PRACTICE
}
