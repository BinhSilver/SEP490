package com.jlas.learning.dto;

import lombok.Builder;
import lombok.Data;

/** Response thông tin lộ trình học của người dùng. */
@Data
@Builder
public class LearningPathResponse {
    private Long id;
    private Long userId;
    private String currentLevel;
    private int completedLessons;
}
