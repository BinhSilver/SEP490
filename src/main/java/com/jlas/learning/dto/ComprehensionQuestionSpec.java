package com.jlas.learning.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/** Internal, server-owned question shape. Answer fields must never be serialized to a learner. */
public record ComprehensionQuestionSpec(
        Long id,
        String prompt,
        List<String> options,
        @JsonIgnore int correctOption,
        @JsonIgnore String explanation) {
    public ComprehensionQuestionSpec {
        options = List.copyOf(options);
    }
}
