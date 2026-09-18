package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/** Unit của giáo trình kèm danh sách bài học. */
@Data
@Builder
public class UnitResponse {
    private Long id;
    private String title;
    private String description;
    private Integer orderIndex;
    private List<LessonSummaryResponse> lessons;
}
