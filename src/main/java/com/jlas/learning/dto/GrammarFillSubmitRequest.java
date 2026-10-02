package com.jlas.learning.dto;

import jakarta.validation.constraints.NotBlank;

/** [JLAS-W0193] Task 6 — the learner's typed answer for the fill-blank practice. */
public record GrammarFillSubmitRequest(@NotBlank String answer) { }
