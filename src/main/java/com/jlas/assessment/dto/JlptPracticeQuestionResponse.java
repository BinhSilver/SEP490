package com.jlas.assessment.dto;

import java.math.BigDecimal;
import java.util.List;

public record JlptPracticeQuestionResponse(
        long id,
        String examCode,
        String examTitle,
        String level,
        String section,
        String typeCode,
        String typeName,
        String instructionHtml,
        int number,
        String text,
        String furigana,
        String html,
        Integer correctOption,
        BigDecimal score,
        String imageUrl,
        JlptExamResponse.Passage passage,
        JlptExamResponse.AudioClip audioClip,
        String mondaiAudioUrl,
        List<JlptExamResponse.TranscriptSegment> transcript,
        List<JlptExamResponse.Option> options,
        List<Integer> correctOrder) {
}
