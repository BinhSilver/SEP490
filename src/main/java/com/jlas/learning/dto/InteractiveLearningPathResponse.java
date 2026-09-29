package com.jlas.learning.dto;

import java.util.List;

public record InteractiveLearningPathResponse(
        Long id,
        String title,
        String goal,
        String currentLevel,
        String targetLevel,
        Integer minutesPerDay,
        int completedPackages,
        int totalPackages,
        int progressPercent,
        Long currentPackageId,
        List<InteractiveLearningPackageResponse> packages) {
    public InteractiveLearningPathResponse {
        packages = List.copyOf(packages);
    }
}
