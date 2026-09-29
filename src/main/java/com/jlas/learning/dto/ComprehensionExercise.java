package com.jlas.learning.dto;

import java.util.List;

/** A deterministic, playable Reading or Listening exercise resolved from the canonical question bank. */
public record ComprehensionExercise(
        String fingerprint,
        String skill,
        String jlptLevel,
        String source,
        String outcomeCode,
        String topicCode,
        List<ComprehensionQuestionSpec> questions,
        String sourceRevision) {
    public ComprehensionExercise {
        questions = List.copyOf(questions);
    }
}
