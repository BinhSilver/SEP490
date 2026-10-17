package com.jlas.learning.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Meaning and example chosen by a lesson, independent of the shared dictionary gloss. */
public record VocabularyLessonContext(String meaning, String exampleJa, String exampleVi) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public VocabularyLessonContext {
        if (meaning == null || meaning.isBlank()) throw new IllegalArgumentException("Lesson meaning required");
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot freeze lesson meaning", ex);
        }
    }
}
