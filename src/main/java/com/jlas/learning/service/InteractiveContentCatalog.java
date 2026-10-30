package com.jlas.learning.service;

import com.jlas.learning.dto.InteractiveContentSnapshot;
import com.jlas.learning.dto.ReferenceVocabularyResponse;
import java.util.List;

public interface InteractiveContentCatalog {
    InteractiveContentSnapshot inspect(Long lessonId, String expectedLevel);

    /** Read-only, source-validated reference list for a lesson with one to four words. */
    default List<ReferenceVocabularyResponse> referenceVocabularyForLesson(Long lessonId) {
        return List.of();
    }
}
