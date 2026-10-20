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
        List<InteractiveLearningActivityResponse> activities,
        Integer scorePercent,
        Long nextAvailablePackageId,
        boolean lastPackage,
        boolean justCompleted,
        List<InteractiveLearningSessionResponse> sessions,
        /** Owner decision 2026-10-06: the roadmap unit is the chapter (source lesson) with ordered parts, not a
         * calendar day. Null lesson id for packages without a source lesson (e.g. Smart Test warm-up). */
        Long chapterLessonId,
        String chapterTitle,
        int chapterNumber,
        int segmentIndex,
        int segmentCount,
        /** §2.28 / OD-44: a note when the chapter comes from an owner-opened DRAFT textbook; null otherwise. */
        String sourceReviewNote) {
    public InteractiveLearningPackageResponse {
        activities = List.copyOf(activities);
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }
    public InteractiveLearningPackageResponse(Long id, String phase, int dayNumber, int orderIndex, String title,
            LearningPackageStatus status, int estimatedMinutes, int completedActivities, int totalActivities,
            int progressPercent, LearningActivityType resumeActivityType, String lockReason, LocalDateTime startedAt,
            LocalDateTime completedAt, List<InteractiveLearningActivityResponse> activities, Integer scorePercent,
            Long nextAvailablePackageId, boolean lastPackage, boolean justCompleted,
            List<InteractiveLearningSessionResponse> sessions) {
        this(id, phase, dayNumber, orderIndex, title, status, estimatedMinutes, completedActivities, totalActivities,
                progressPercent, resumeActivityType, lockReason, startedAt, completedAt, activities, scorePercent,
                nextAvailablePackageId, lastPackage, justCompleted, sessions, null, title, 1, 1, 1, null);
    }
}
