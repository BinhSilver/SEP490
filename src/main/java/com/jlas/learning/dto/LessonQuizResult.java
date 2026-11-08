package com.jlas.learning.dto;

import java.util.List;

/**
 * [JLAS-W0113] Kết quả chấm quiz: đúng/sai từng câu + giải thích, điểm và ngưỡng đạt.
 *
 * [JLAS-W0108] P4/D2 — thêm {@code groupResults} để UI nói rõ nhóm mục tiêu bắt buộc nào còn thiếu
 * (điểm tổng không được che nhóm chưa đạt).
 *
 * [JLAS-W0108] P4 — bỏ {@code lessonCompleted}: đạt quiz KHÔNG đồng nghĩa hoàn thành nội dung (study ≠
 * PASSED). Việc ghi study/assessment được xử lý riêng trên {@code UserLearningPathItem}, quiz result chỉ
 * phản ánh kết quả chấm.
 */
public record LessonQuizResult(
        int totalQuestions,
        int correctAnswers,
        int scorePercent,
        boolean passed,
        int passThresholdPercent,
        boolean relearnRequired,
        List<AnswerReview> review,
        List<GroupResult> groupResults
) {
    public LessonQuizResult {
        review = List.copyOf(review);
        groupResults = groupResults == null ? List.of() : List.copyOf(groupResults);
    }

    public record AnswerReview(
            Long questionId,
            Integer correctOption,
            Integer selectedOption,
            boolean correct,
            String explanation
    ) { }

    /**
     * [JLAS-W0108] P4/D2 — điểm của một nhóm mục tiêu bắt buộc (mẫu ngữ pháp / từ vựng / tag).
     * {@code requiredCorrect} = ceil(threshold × total) để bài ít câu không lọt "đạt" sai.
     */
    public record GroupResult(
            String groupKey,
            String groupLabel,
            int correct,
            int total,
            int requiredCorrect,
            boolean passed
    ) { }
}
