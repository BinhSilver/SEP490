package com.jlas.assessment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record JlptHistorySubmitRequest(
        @NotBlank @Size(max = 36) String submissionId,
        @NotBlank @Pattern(regexp = "N[1-5]_(?:\\d{4}_(?:07|12)|MOCK_\\d+)") String examCode,
        @NotBlank @Pattern(regexp = "EXAM|PRACTICE") String mode,
        @Pattern(regexp = "vocabulary|grammar_reading|listening") String partCode,
        Map<Long, Integer> answers,
        Map<Long, List<Integer>> sentenceOrders) {
}
