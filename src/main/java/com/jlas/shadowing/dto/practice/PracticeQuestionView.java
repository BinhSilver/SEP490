package com.jlas.shadowing.dto.practice;

public record PracticeQuestionView(
        long itemId,
        long subtitleId,
        int number,
        int total,
        int difficulty,
        String difficultyLabel,
        double startTime,
        double endTime,
        String promptText,
        String translation,
        boolean answered,
        PracticeAnswerResponse previousAnswer,
        String kind,
        String level,
        java.util.List<String> choices,
        String explanation) {
}
