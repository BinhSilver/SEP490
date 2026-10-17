package com.jlas.learning.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** [JLAS-W0113] One autosaved selection for a snapshot question. A null {@code selectedOption} unselects
 * (clears an MCQ choice / unpairs a matching pair). Shared by the autosave request and the issued response so
 * the client restores exactly what it saved. Autosave never grades — this is a selection, not an answer. */
public record MicroCheckDraftAnswer(
        @NotNull Long questionId,
        @Min(1) @Max(100) Integer selectedOption
) { }
