package com.jlas.common.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Event contract emitted by learning features and consumed by mission, daily goal and streak.
 *
 * <p>{@code eventId} is deterministic for a given business fact (for example one lesson completion),
 * so a replayed or re-published event is recognised and processed only once.
 *
 * @param referenceId     lesson id, quiz attempt id or video id depending on {@code type}
 * @param minutes         study minutes not yet reported for this lesson (producers report deltas)
 * @param vocabularyCount distinct vocabulary entries covered by the lesson
 * @param occurredAt      when the learner did it; the learner's day is derived from this, not from processing time
 */
public record LearningEvent(String eventId, LearningEventType type, Long userId, Long referenceId,
                            int minutes, int vocabularyCount, Instant occurredAt) {

    public static final int MAX_EVENT_ID_LENGTH = 120;

    public LearningEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (eventId.isBlank() || eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw new IllegalArgumentException("eventId must be 1.." + MAX_EVENT_ID_LENGTH + " characters");
        }
        minutes = Math.max(0, minutes);
        vocabularyCount = Math.max(0, vocabularyCount);
    }

    public static LearningEvent lessonStarted(Long userId, Long lessonId) {
        return new LearningEvent(key(LearningEventType.LESSON_STARTED, userId, lessonId),
                LearningEventType.LESSON_STARTED, userId, lessonId, 0, 0, Instant.now());
    }

    /** {@code sequence} must grow for each report of the same lesson so each delta is counted once. */
    public static LearningEvent lessonProgressUpdated(Long userId, Long lessonId, long sequence, int minutes) {
        return new LearningEvent(key(LearningEventType.LESSON_PROGRESS_UPDATED, userId, lessonId) + ":" + sequence,
                LearningEventType.LESSON_PROGRESS_UPDATED, userId, lessonId, minutes, 0, Instant.now());
    }

    public static LearningEvent lessonCompleted(Long userId, Long lessonId, int minutes, int vocabularyCount) {
        return new LearningEvent(key(LearningEventType.LESSON_COMPLETED, userId, lessonId),
                LearningEventType.LESSON_COMPLETED, userId, lessonId, minutes, vocabularyCount, Instant.now());
    }

    public static LearningEvent lessonQuizCompleted(Long userId, Long attemptId) {
        return new LearningEvent(key(LearningEventType.LESSON_QUIZ_COMPLETED, userId, attemptId),
                LearningEventType.LESSON_QUIZ_COMPLETED, userId, attemptId, 0, 0, Instant.now());
    }

    public static LearningEvent shadowingPublished(Long userId, Long videoId) {
        return new LearningEvent(key(LearningEventType.SHADOWING_PUBLISHED, userId, videoId),
                LearningEventType.SHADOWING_PUBLISHED, userId, videoId, 0, 0, Instant.now());
    }

    private static String key(LearningEventType type, Long userId, Long referenceId) {
        return type.name() + ":" + userId + ":" + referenceId;
    }
}
