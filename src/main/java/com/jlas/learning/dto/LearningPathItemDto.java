package com.jlas.learning.dto;

import java.util.List;

/** One scheduled lesson inside a learning path phase. */
public record LearningPathItemDto(Long itemId, Long lessonId, String title, Long textbookId, Long unitId, String level,
        String reasonCode, String reasonText, List<String> matchedTagCodes,
        String knowledgePointCode, String knowledgePointName,
        Long grammarPointId, String grammarPointPattern, String grammarPointMeaning,
        Long vocabularyEntryId, String vocabularyExpression, String vocabularyReading, String vocabularyMeaning,
        int dayNumber, int orderIndex, int estimatedMinutes, boolean completed, boolean started) {
    public LearningPathItemDto {
        matchedTagCodes = List.copyOf(matchedTagCodes);
    }
}
