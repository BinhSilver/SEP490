package com.jlas.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * [JLAS-W0113] Bài nộp quiz: danh sách câu trả lời. selectedOption null = bỏ qua câu.
 */
public record LessonQuizSubmitRequest(@NotEmpty List<@Valid LessonQuizAnswerRequest> answers) {
    public record LessonQuizAnswerRequest(
            @NotNull Long questionId,
            @Min(1) @Max(4) Integer selectedOption
    ) { }
}
