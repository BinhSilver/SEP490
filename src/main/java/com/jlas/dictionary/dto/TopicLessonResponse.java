package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Bài học trong một chủ đề, kèm ngữ cảnh giáo trình/unit vì một chủ đề trải nhiều giáo trình (N5, N4...).
 * Link mở bài: /textbooks/{textbookId}/lessons/{id}.
 */
@Data
@Builder
public class TopicLessonResponse {
    private Long id;
    private String title;
    private String description;
    private String lessonType;
    private String skill;
    private String status;
    private Integer orderIndex;
    private Integer estimatedMinutes;
    private Long unitId;
    private String unitTitle;
    private Long textbookId;
    private String textbookTitle;
    private String jlptLevel;
}
