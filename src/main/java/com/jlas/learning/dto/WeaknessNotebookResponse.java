package com.jlas.learning.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record WeaknessNotebookResponse(List<Entry> items, long total, int page, int size,
        Summary summary, Assessment latestAssessment) {
    public record Entry(Long id, String title, String description, String example, String reading, String exampleTranslation,
            String skill, String level, String status, int wrongCount, BigDecimal accuracy,
            String topic, String source, LocalDateTime detectedAt, LocalDateTime lastReviewedAt,
            String studyUrl) {}
    public record Summary(long total, long open, long reviewed, long mastered, Map<String, Long> skills) {}
    public record Assessment(Long id, String assessedLevel, String targetLevel,
            String readiness, LocalDateTime submittedAt) {}
}
