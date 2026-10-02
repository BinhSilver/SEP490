package com.jlas.learning.dto;

import java.util.List;

/** Body of {@code POST /api/learning/quizzes/attempts/{attemptId}/submit}. */
public record QuizSubmitRequest(List<QuizAnswerSubmission> answers) {
}
