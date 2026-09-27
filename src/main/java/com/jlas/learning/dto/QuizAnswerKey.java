package com.jlas.learning.dto;

/**
 * [JLAS-W0108] Đáp án đúng của một câu trong snapshot đề thi (lưu trong {@code learning_quiz_session}).
 * Không bao giờ trả về client; chỉ dùng để chấm bài và dựng lại kết quả khi nộp lại (idempotent).
 */
public record QuizAnswerKey(int correctOption, String explanation) { }
