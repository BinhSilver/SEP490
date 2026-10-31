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
        List<InteractiveLearningPackageResponse> packages,
        List<SmartTestReviewTarget> smartTestReviewOnlyTargets,
        CommunicationGuide communicationGuide,
        List<String> focusSkills) {
    public InteractiveLearningPathResponse {
        packages = List.copyOf(packages);
        smartTestReviewOnlyTargets = List.copyOf(smartTestReviewOnlyTargets);
        focusSkills = focusSkills == null ? List.of() : List.copyOf(focusSkills);
    }

    /** Without focus skills (F1-5): every response before 2026-10-28. */
    public InteractiveLearningPathResponse(Long id, String title, String goal, String currentLevel,
            String targetLevel, Integer minutesPerDay, int completedPackages, int totalPackages,
            int progressPercent, Long currentPackageId, List<InteractiveLearningPackageResponse> packages,
            List<SmartTestReviewTarget> smartTestReviewOnlyTargets, CommunicationGuide communicationGuide) {
        this(id, title, goal, currentLevel, targetLevel, minutesPerDay, completedPackages, totalPackages,
                progressPercent, currentPackageId, packages, smartTestReviewOnlyTargets, communicationGuide, List.of());
    }

    public InteractiveLearningPathResponse(Long id, String title, String goal, String currentLevel,
            String targetLevel, Integer minutesPerDay, int completedPackages, int totalPackages,
            int progressPercent, Long currentPackageId, List<InteractiveLearningPackageResponse> packages,
            List<SmartTestReviewTarget> smartTestReviewOnlyTargets) {
        this(id, title, goal, currentLevel, targetLevel, minutesPerDay, completedPackages, totalPackages,
                progressPercent, currentPackageId, packages, smartTestReviewOnlyTargets, null);
    }

    public InteractiveLearningPathResponse(Long id, String title, String goal, String currentLevel,
            String targetLevel, Integer minutesPerDay, int completedPackages, int totalPackages,
            int progressPercent, Long currentPackageId, List<InteractiveLearningPackageResponse> packages) {
        this(id, title, goal, currentLevel, targetLevel, minutesPerDay, completedPackages, totalPackages,
                progressPercent, currentPackageId, packages, List.of(), null);
    }
}
