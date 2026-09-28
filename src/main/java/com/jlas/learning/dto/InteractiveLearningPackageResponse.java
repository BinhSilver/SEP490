package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningPackageStatus;

import java.time.LocalDateTime;
import java.util.List;

public record InteractiveLearningPackageResponse(
        Long id,
        String phase,
        int dayNumber,
        int orderIndex,
        String title,
        LearningPackageStatus status,
        int estimatedMinutes,
        int completedActivities,
        int totalActivities,
        int progressPercent,
        LearningActivityType resumeActivityType,
        String lockReason,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<InteractiveLearningActivityResponse> activities) {
    public InteractiveLearningPackageResponse {
        activities = List.copyOf(activities);
    }
}
