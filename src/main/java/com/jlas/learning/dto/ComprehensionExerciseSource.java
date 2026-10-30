package com.jlas.learning.dto;

public record ComprehensionExerciseSource(String passage, String audioUrl, String transcriptJa) {
    public ComprehensionExerciseSource(String passage, String audioUrl) {
        this(passage, audioUrl, null);
    }
}
