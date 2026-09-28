package com.jlas.learning.dto;

/**
 * [JLAS-W0108] Đáp án đúng của một câu trong snapshot đề thi (lưu trong {@code learning_quiz_session}).
 * Không bao giờ trả về client; chỉ dùng để chấm bài và dựng lại kết quả khi nộp lại (idempotent).
 *
 * [JLAS-W0108] P4/D2 — mang thêm {@code groupKey}/{@code groupLabel} để chấm theo từng nhóm mục tiêu
 * bắt buộc (mỗi mẫu ngữ pháp / từ vựng là một nhóm); quiz nền tảng (Kana) và gate để null.
 */
public record QuizAnswerKey(int correctOption, String explanation, String groupKey, String groupLabel) {
    public QuizAnswerKey(int correctOption, String explanation) {
        this(correctOption, explanation, null, null);
    }
}
