package com.jlas.mission.dto;

import lombok.Builder;
import lombok.Data;

/** Response thông tin nhiệm vụ. */
@Data
@Builder
public class MissionResponse {
    private Long id;
    private String title;
    private String type;
    private int targetValue;
    private int rewardPoints;
}
