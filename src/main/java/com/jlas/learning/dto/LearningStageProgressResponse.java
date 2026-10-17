package com.jlas.learning.dto;

/** [JLAS-W0108] Task 9 — per-stage progress from required-package counts; {@code percent} is floored and
 * {@code blocked} is true when the stage has no required package (never treated as complete). */
public record LearningStageProgressResponse(
        Long stageId,
        int stageIndex,
        String level,
        String status,
        int completedPackages,
        int totalPackages,
        int percent,
        boolean blocked) {
}
