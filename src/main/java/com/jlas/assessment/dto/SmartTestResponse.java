package com.jlas.assessment.dto;

import java.math.BigDecimal;
import java.util.List;

public record SmartTestResponse(
        Long attemptId,
        String status,
        int answeredCount,
        int totalQuestions,
        List<String> selectedSkills,
        QuestionView currentQuestion,
        ResultView result
) {
    public record QuestionView(
            Long id,
            int number,
            int batchNumber,
            String skill,
            String jlptLevel,
            String tagCode,
            String difficulty,
            String title,
            String passageHtml,
            String audioUrl,
            String imageUrl,
            String questionText,
            List<String> options
    ) { }

    public record ResultView(
            String estimatedOverallLevel,
            String abilityBand,
            BigDecimal overallMasteryScore,
            String confidence,
            String readinessLevel,
            String targetLevel,
            boolean partial,
            int correctAnswers,
            int totalQuestions,
            int selectedSkillCount,
            List<String> recommendations,
            List<SkillResultView> skills
    ) { }

    public record SkillResultView(
            String skill,
            String estimatedLevel,
            String abilityBand,
            BigDecimal masteryScore,
            String confidence,
            int correctAnswers,
            int totalQuestions,
            String recommendation,
            List<BatchResultView> batches
    ) { }

    public record BatchResultView(
            int batchNumber,
            String level,
            int correctAnswers,
            int totalQuestions
    ) { }
}
