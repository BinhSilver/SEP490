package com.jlas.assessment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

/** Answers are re-graded against the server-owned JLPT bank; no score/pass flag is accepted. */
public record JlptMockSubmissionRequest(
        @NotBlank String examCode,
        @NotEmpty Map<Long, Integer> answers) { }
