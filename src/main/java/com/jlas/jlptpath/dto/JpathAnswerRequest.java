package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * [JLAS-W0108] Answer to one question: an option id (MCQ), piece ids "p3,p1,p2" (ORDER) or text (FILL_TEXT).
 * {@code value} null or blank = time ran out (wrong). Probe L5 (15/11): at most 1000 characters (a dictation line's
 * JSON array of short blanks fits well inside it).
 */
public record JpathAnswerRequest(@NotBlank String questionId, @Size(max = 1000) String value, Long elapsedMs) {
}
