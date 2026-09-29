package com.jlas.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** [JLAS-W0193] Micro-check submit: the issued attempt id plus the learner's selected options. */
public record MicroCheckSubmitRequest(
        @NotNull Long attemptId,
        @NotEmpty List<@Valid MicroCheckAnswer> answers
) {
    public record MicroCheckAnswer(
            @NotNull Long questionId,
            @Min(1) @Max(100) Integer selectedOption
    ) { }
}
