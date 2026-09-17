package com.jlas.common.event;

import java.time.Instant;

/**
 * Event emitted by learning features; retention/mission may consume it independently.
 *
 * @param minutes          credited study minutes (already capped to the lesson estimate)
 * @param vocabularyCount  number of distinct vocabulary entries covered by the lesson
 */
public record LearningActivityCompleted(Long userId, String activityType, Long referenceId,
                                        int minutes, int vocabularyCount, Instant occurredAt) {

    public static LearningActivityCompleted shadowingPublished(Long userId, Long videoId) {
        return new LearningActivityCompleted(userId, "PUBLISH_SHADOWING", videoId, 0, 0, Instant.now());
    }

    public static LearningActivityCompleted lessonCompleted(Long userId, Long lessonId,
                                                            int minutes, int vocabularyCount) {
        return new LearningActivityCompleted(userId, "COMPLETE_LESSON", lessonId,
                minutes, vocabularyCount, Instant.now());
    }

    public static LearningActivityCompleted quizCompleted(Long userId, Long quizId) {
        return new LearningActivityCompleted(userId, "COMPLETE_QUIZ", quizId, 0, 0, Instant.now());
    }
}
