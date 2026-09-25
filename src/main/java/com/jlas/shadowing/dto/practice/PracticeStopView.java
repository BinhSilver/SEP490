package com.jlas.shadowing.dto.practice;

public record PracticeStopView(
        long subtitleId,
        int number,
        int difficulty,
        String difficultyLabel,
        double startTime,
        double endTime) {
}
