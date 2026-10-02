package com.jlas.assessment.dto;

import java.math.BigDecimal;
import java.util.List;

public record JlptExamResponse(
        String code,
        String title,
        String level,
        Integer year,
        Integer month,
        String reviewStatus,
        List<Part> parts) {

    public record Part(long id, String code, String title, Integer durationMinutes, List<Mondai> mondai) {}

    public record Mondai(
            long id,
            int number,
            String format,
            String instructionHtml,
            String audioUrl,
            List<Question> questions,
            List<TranscriptSegment> transcript) {}

    public record Question(
            long id,
            long sourceId,
            int number,
            String text,
            String furigana,
            String html,
            String questionType,
            Integer correctOption,
            BigDecimal score,
            String imageUrl,
            Integer sourcePage,
            String explanation,
            Passage passage,
            AudioClip audioClip,
            List<Option> options,
            List<Integer> correctOrder) {}

    public record Option(int number, String text, String furigana, String html) {}

    public record Passage(Long sourceId, String ja, String html, String vi) {}

    public record AudioClip(
            long id,
            String type,
            String audioUrl,
            double start,
            double end,
            List<TranscriptSegment> transcript,
            boolean transcriptAligned) {
        public AudioClip(long id, String type, String audioUrl, double start, double end,
                List<TranscriptSegment> transcript) {
            this(id, type, audioUrl, start, end, transcript,
                    audioUrl == null || !audioUrl.startsWith("/audio/JLPT/N3/"));
        }
    }

    public record TranscriptSegment(double start, double end, String ja, String furigana, String vi) {}
}
