package com.jlas.learning.service;

import com.jlas.learning.dto.LessonDetailResponse;
import java.util.Optional;

/** Reads a single published catalog lesson for the learning-path lesson detail page. */
public interface LessonDetailService {
    /** Returns the published lesson, or empty when it does not exist or is not published. */
    Optional<LessonDetailResponse> findPublished(Long lessonId);
}
