package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * [JLAS-W0108] Answer to one question: an option id (MCQ), piece ids "p3,p1,p2" (ORDER) or text (FILL_TEXT).
 * {@code value} null or blank = time ran out (wrong).
 */
public record JpathAnswerRequest(@NotBlank String questionId, String value, Long elapsedMs) {
}
