package com.jlas.learning.dto;

import java.util.List;

/** [JLAS-W0193] Issued micro-check snapshot (questions only; the answer key never leaves the server). */
public record MicroCheckResponse(
        Long attemptId,
        Long activityId,
        int batchIndex,
        int batchCount,
        int totalQuestions,
        List<MicroCheckQuestion> questions
) {
    public MicroCheckResponse {
        questions = List.copyOf(questions);
    }

    public record MicroCheckQuestion(
            Long questionId,
            String questionType,
            String questionText,
            List<String> options
    ) {
        public MicroCheckQuestion {
            options = List.copyOf(options);
        }
    }
}
