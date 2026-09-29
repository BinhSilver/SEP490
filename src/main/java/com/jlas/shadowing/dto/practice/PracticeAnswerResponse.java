package com.jlas.shadowing.dto.practice;

import com.jlas.shadowing.model.practice.AnswerResult;
import java.util.List;

public record PracticeAnswerResponse(
        AnswerResult result,
        int score,
        String feedback,
        String expectedAnswer,
        String userAnswer,
        String translation,
        List<PracticeDiffPart> differences,
        String explanation) {
}
