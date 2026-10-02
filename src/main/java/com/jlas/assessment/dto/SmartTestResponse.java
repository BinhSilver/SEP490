package com.jlas.assessment.dto;

import java.math.BigDecimal;
import java.util.List;

public record SmartTestResponse(
        Long attemptId,
        String status,
        String goal,
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
            String recommendedTarget,
            List<String> recommendedStageLevels,
            boolean requiresTargetConfirmation,
            boolean partial,
            int correctAnswers,
            int totalQuestions,
            int selectedSkillCount,
            List<String> recommendations,
            List<SkillResultView> skills,
            List<AnswerReviewView> review
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
            List<BatchResultView> batches,
            List<GrammarPointResultView> grammarPoints
    ) { }

    public record BatchResultView(
            int batchNumber,
            String level,
            int correctAnswers,
            int totalQuestions
    ) { }

    /** [JLAS2-24] Điểm yếu theo từng mẫu ngữ pháp (chỉ mẫu chính được tính điểm). */
    public record GrammarPointResultView(
            Long grammarPointId,
            String pattern,
            String lessonCode,
            String lessonTitle,
            int correctAnswers,
            int totalQuestions,
            BigDecimal masteryScore
    ) { }

    /** [JLAS-W0090] Ôn tập từng câu đã trả lời: đúng/sai + giải thích (không lộ tag nội bộ). */
    public record AnswerReviewView(
            Long id,
            int number,
            String skill,
            String jlptLevel,
            String questionText,
            List<String> options,
            Integer correctOption,
            Integer selectedOption,
            boolean correct,
            String explanation,
            String grammarPattern,
            String grammarMeaning
    ) { }
}
