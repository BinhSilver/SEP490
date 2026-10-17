package com.jlas.learning.dto;

import java.util.List;

/** [JLAS-W0108] A Reading/Listening practice replay: the questions together with the frozen source of exactly that batch. */
public record ComprehensionReplayResponse(Long attemptId, Long activityId, int batchIndex, int batchCount,
        ComprehensionExerciseSource source, List<MicroCheckResponse.MicroCheckQuestion> questions) {
    public ComprehensionReplayResponse {
        questions = List.copyOf(questions);
    }
}
