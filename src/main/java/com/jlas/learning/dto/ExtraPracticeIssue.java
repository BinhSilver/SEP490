package com.jlas.learning.dto;

import java.util.List;

/** §2.32: an issued extra-practice passage with its frozen source; {@code label} as in {@link ExtraPracticeSuggestions}. */
public record ExtraPracticeIssue(Long attemptId, Long activityId, String passage, ComprehensionExerciseSource source,
        List<MicroCheckResponse.MicroCheckQuestion> questions, String label) {
    public ExtraPracticeIssue {
        questions = List.copyOf(questions);
    }
}
