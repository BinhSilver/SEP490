package com.jlas.assessment.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record JlptMockResultResponse(
        Long attemptId, String examCode, String level, int correctCount, int questionCount,
        int scorePercent, boolean passed, Map<String, SectionResult> sections, LocalDateTime submittedAt) {
    public record SectionResult(int correctCount, int questionCount, int scorePercent, boolean passed) { }
}
