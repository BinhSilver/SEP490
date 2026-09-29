package com.jlas.mission.service;

import com.jlas.mission.dto.ClaimMissionResponse;
import com.jlas.mission.dto.MissionItemResponse;
import com.jlas.mission.entity.MissionDefinition;
import com.jlas.mission.enums.MissionCode;
import java.time.LocalDate;
import java.util.List;

/** Daily missions: progress from learning events and idempotent reward claims. */
public interface DailyMissionService {

    /** Advances a daily mission for the learner's {@code day}; joins the caller's transaction. */
    void advance(Long userId, MissionCode code, LocalDate day, int amount);

    List<MissionItemResponse> list(Long userId, LocalDate day);

    /** Claims today's reward. A repeated claim returns {@code alreadyClaimed=true} and awards nothing. */
    ClaimMissionResponse claim(Long userId, String code);

    /** Marks a once-per-lifetime mission (streak milestone) done; returns false if it already was. */
    boolean completeLifetime(Long userId, MissionDefinition definition);

    List<MissionDefinition> activeStreakMilestones();
}
