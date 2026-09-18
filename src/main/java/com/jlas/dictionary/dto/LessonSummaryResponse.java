package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;

/** Tóm tắt một bài học trong danh sách unit của giáo trình. */
@Data
@Builder
public class LessonSummaryResponse {
    private Long id;
    private String title;
    private String description;
    private String lessonType;
    private String skill;
    private String status;
    private Integer orderIndex;
    private Integer estimatedMinutes;
}
