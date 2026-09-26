package com.jlas.learning.dto;

import java.util.List;

/**
 * [JLAS-W0113] Kết quả chấm quiz: đúng/sai từng câu + giải thích, điểm và ngưỡng đạt.
 */
public record LessonQuizResult(
        int totalQuestions,
        int correctAnswers,
        int scorePercent,
        boolean passed,
        int passThresholdPercent,
        boolean lessonCompleted,
        boolean relearnRequired,
        List<AnswerReview> review
) {
    public LessonQuizResult {
        review = List.copyOf(review);
    }

    public record AnswerReview(
            Long questionId,
            Integer correctOption,
            Integer selectedOption,
            boolean correct,
            String explanation
    ) { }
}
