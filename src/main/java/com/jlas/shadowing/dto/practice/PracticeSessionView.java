package com.jlas.shadowing.dto.practice;

import com.jlas.shadowing.model.practice.PracticeMode;
import com.jlas.shadowing.model.practice.PracticeOrder;

import java.util.List;

public record PracticeSessionView(
        long sessionId,
        long videoId,
        String videoTitle,
        boolean audioOnly,
        PracticeMode mode,
        PracticeOrder order,
        int currentPosition,
        int totalQuestions,
        int correctCount,
        int averageScore,
        boolean completed,
        List<PracticeStopView> stops,
        PracticeQuestionView question) {
}
