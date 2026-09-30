package com.jlas.common.event;

/** Learning event types agreed between learning producers and the mission/credit consumers. */
public enum LearningEventType {
    LESSON_STARTED,
    LESSON_PROGRESS_UPDATED,
    LESSON_COMPLETED,
    LESSON_QUIZ_COMPLETED,
    SHADOWING_PUBLISHED,
    /** [JLAS-W0108] Interactive learning path completed via a verified JLPT Mock Test; once per path. */
    PATH_COMPLETED
}
