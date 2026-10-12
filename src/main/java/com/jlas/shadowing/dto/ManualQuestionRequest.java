package com.jlas.shadowing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Only content is accepted; ownership, timings, hashes and publication status are server controlled. */
public record ManualQuestionRequest(
        @NotNull @Size(min = 1, max = 6) List<@NotNull @Positive Long> sourceIds,
        @NotNull @Size(min = 1, max = 6) List<@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String> sourceHashes,
        @NotBlank @Pattern(regexp = "GAP|DETAIL|MAIN_IDEA|ACTION_SEQUENCE|REASON_PURPOSE|PROBLEM_SOLUTION|ATTITUDE_DECISION") String kind,
        @NotBlank @Pattern(regexp = "EASY|MEDIUM|HARD") String level,
        @Size(max = 2000) String prompt,
        @NotBlank @Size(max = 500) String answer,
        @NotNull @Size(max = 4) List<@NotBlank @Size(max = 500) String> choices,
        @NotBlank @Size(max = 4000) String explanation) {
}
