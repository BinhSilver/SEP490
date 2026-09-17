package com.jlas.common.event;

import java.time.Instant;

/** Event emitted by learning features; retention/mission may consume it independently. */
public record LearningActivityCompleted(Long userId, String activityType,
                                        Long referenceId, int minutes, Instant occurredAt) {

    public static LearningActivityCompleted shadowingPublished(Long userId, Long videoId) {
        return new LearningActivityCompleted(userId, "PUBLISH_SHADOWING", videoId, 0, Instant.now());
    }

    public static LearningActivityCompleted lessonCompleted(Long userId, Long lessonId, int minutes) {
        return new LearningActivityCompleted(userId, "COMPLETE_LESSON", lessonId, minutes, Instant.now());
    }

    public static LearningActivityCompleted quizCompleted(Long userId, Long quizId) {
        return new LearningActivityCompleted(userId, "COMPLETE_QUIZ", quizId, 0, Instant.now());
    }
}
