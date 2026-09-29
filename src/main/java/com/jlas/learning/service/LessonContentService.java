package com.jlas.learning.service;

import com.jlas.learning.dto.LessonContentResponse;
import java.util.Optional;

/** Reads a published lesson's content (sections, content items, grammar points). */
public interface LessonContentService {

    /** Returns the content of a published lesson, or empty when the lesson is missing/not published. */
    Optional<LessonContentResponse> findPublished(Long lessonId);
}
