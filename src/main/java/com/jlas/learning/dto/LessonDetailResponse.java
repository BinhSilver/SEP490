package com.jlas.learning.dto;

/** Read model for the catalog-lesson detail page reached from a learning path. */
public record LessonDetailResponse(
        Long id,
        String title,
        String description,
        String skill,
        String textbookLevel,
        Integer estimatedMinutes,
        String knowledgeTagName,
        String textbookTitle,
        String unitTitle) {
}
