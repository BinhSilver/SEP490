package com.jlas.common.event;

import java.time.Instant;

/** Event emitted by learning features; retention/mission may consume it independently. */
public record LearningActivityCompleted(Long userId, String activityType,
                                        Long referenceId, Instant occurredAt) {
    public static LearningActivityCompleted shadowingPublished(Long userId, Long videoId) {
        return new LearningActivityCompleted(userId, "PUBLISH_SHADOWING", videoId, Instant.now());
    }
}
