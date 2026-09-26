package com.jlas.learning.dto;

import java.util.Set;

public record LearningResourceCandidate(Long lessonId, String title, Long textbookId, Long unitId,
        Set<String> knowledgeTagCodes, String level, Integer estimatedMinutes,
        Integer unitOrder, Integer lessonOrder,
        Long grammarPointId, Long vocabularyEntryId) {
    public LearningResourceCandidate {
        knowledgeTagCodes = Set.copyOf(knowledgeTagCodes);
    }
}
