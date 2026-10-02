package com.jlas.learning.dto;

import java.util.Set;

public record LearningResourceCandidate(Long lessonId, String title, Long textbookId, Long unitId,
        Set<String> knowledgeTagCodes, String level, Integer estimatedMinutes,
        Integer unitOrder, Integer lessonOrder,
        Long grammarPointId, Long vocabularyEntryId, String skill) {
    public LearningResourceCandidate {
        knowledgeTagCodes = Set.copyOf(knowledgeTagCodes);
    }

    /** [JLAS-W0108] Tương thích ngược: candidate không có skill mặc định null (test/adapters). */
    public LearningResourceCandidate(Long lessonId, String title, Long textbookId, Long unitId,
            Set<String> knowledgeTagCodes, String level, Integer estimatedMinutes,
            Integer unitOrder, Integer lessonOrder,
            Long grammarPointId, Long vocabularyEntryId) {
        this(lessonId, title, textbookId, unitId, knowledgeTagCodes, level, estimatedMinutes,
                unitOrder, lessonOrder, grammarPointId, vocabularyEntryId, null);
    }
}
