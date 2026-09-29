package com.jlas.learning.dto;

import java.util.List;

/** One scheduled lesson inside a learning path phase. */
public record LearningPathItemDto(Long lessonId, String title, Long textbookId, Long unitId, String level,
        String reasonCode, String reasonText, List<String> matchedTagCodes,
        int dayNumber, int orderIndex, int estimatedMinutes, boolean completed) {
    public LearningPathItemDto {
        matchedTagCodes = List.copyOf(matchedTagCodes);
    }
}
