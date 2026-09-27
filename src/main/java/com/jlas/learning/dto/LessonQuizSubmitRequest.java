package com.jlas.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * [JLAS-W0113] Bài nộp quiz: danh sách câu trả lời. selectedOption null = bỏ qua câu.
 *
 * [JLAS-W0108] {@code attemptId} trỏ về session đề thi đã phát; server chấm theo snapshot của session
 * đó (mẫu số = toàn bộ câu đã phát), từ chối câu ngoài đề / trùng id, và nộp lại chỉ trả kết quả đã lưu.
 */
public record LessonQuizSubmitRequest(
        @NotNull Long attemptId,
        @NotEmpty List<@Valid LessonQuizAnswerRequest> answers) {
    public record LessonQuizAnswerRequest(
            @NotNull Long questionId,
            // [JLAS-W0108] Quiz bảng chữ cái (nối cặp) có thể có nhiều hơn 4 lựa chọn romaji.
            @Min(1) @Max(100) Integer selectedOption
    ) { }
}
