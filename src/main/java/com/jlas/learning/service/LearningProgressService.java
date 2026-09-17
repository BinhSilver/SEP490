package com.jlas.learning.service;

import com.jlas.learning.dto.LessonProgressResponse;

/** Tracks a user's start/resume/complete state on a single published lesson. */
public interface LearningProgressService {

    /** Marks the lesson in progress (idempotent; a completed lesson is never regressed). */
    LessonProgressResponse start(Long userId, Long lessonId);

    /** Marks the lesson completed (idempotent). */
    LessonProgressResponse complete(Long userId, Long lessonId);

    /** Returns the current progress, or a NOT_STARTED state when the lesson was never opened. */
    LessonProgressResponse status(Long userId, Long lessonId);
}
