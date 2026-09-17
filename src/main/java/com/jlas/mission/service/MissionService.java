package com.jlas.mission.service;

import com.jlas.mission.dto.MissionSummaryResponse;

/** Aggregates the learner's daily goal, credit, and streak state. */
public interface MissionService {

    MissionSummaryResponse summary(Long userId);
}
