package com.jlas.learning.dto;

import java.util.List;

/** [JLAS-W0193] Issued micro-check snapshot (questions only; the answer key never leaves the server).
 * [JLAS-W0113] {@code draftAnswers} carries the learner's previously autosaved selections so the client can
 * restore them on reopen/reload; empty on a fresh issue or a replay. */
public record MicroCheckResponse(
        Long attemptId,
        Long activityId,
        int batchIndex,
        int batchCount,
        int totalQuestions,
        List<MicroCheckQuestion> questions,
        List<MicroCheckDraftAnswer> draftAnswers
) {
    public MicroCheckResponse {
        questions = List.copyOf(questions);
        draftAnswers = draftAnswers == null ? List.of() : List.copyOf(draftAnswers);
    }

    /** Fresh-issue / replay convenience constructor: no draft yet. */
    public MicroCheckResponse(Long attemptId, Long activityId, int batchIndex, int batchCount, int totalQuestions,
            List<MicroCheckQuestion> questions) {
        this(attemptId, activityId, batchIndex, batchCount, totalQuestions, questions, List.of());
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
