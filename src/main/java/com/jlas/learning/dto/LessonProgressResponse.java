package com.jlas.learning.dto;

import com.jlas.learning.enums.LessonStatus;
import java.time.LocalDateTime;

/** Read model for a user's progress on a single lesson. */
public record LessonProgressResponse(
        Long lessonId,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt) {

    /** Synthetic NOT_STARTED state for a lesson the user has never opened. */
    public static LessonProgressResponse notStarted(Long lessonId) {
        return new LessonProgressResponse(lessonId, LessonStatus.NOT_STARTED.name(), null, null);
    }
}
