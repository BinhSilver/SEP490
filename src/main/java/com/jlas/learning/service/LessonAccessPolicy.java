package com.jlas.learning.service;

/**
 * Lets a course refuse quizzes the learner has not unlocked yet (the Onyomi course opens its lessons one
 * after another). Lessons and units outside a policy's course always pass that policy.
 */
public interface LessonAccessPolicy {

    /** Throws an {@code ApiException} when the lesson is locked for the user. */
    void checkLesson(Long userId, Long lessonId);

    /** Throws an {@code ApiException} when the unit's final test is locked for the user. */
    void checkUnitFinal(Long userId, Long unitId);

    /** Throws an {@code ApiException} when the learner may not mark the lesson done by hand (a quiz decides it). */
    void checkManualCompletion(Long userId, Long lessonId);
}
