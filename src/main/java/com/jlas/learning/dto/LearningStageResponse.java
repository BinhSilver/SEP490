package com.jlas.learning.dto;

/**
 * [JLAS-W0108] P6 — read model của một chặng học trong lộ trình nhiều cấp, để UI hiển thị
 * "chặng hiện tại" và trạng thái từng chặng mà không phơi bày entity/khóa chính.
 */
public record LearningStageResponse(String level, int stageIndex, String status,
        String curriculumVersion, String assessmentCycle) { }
