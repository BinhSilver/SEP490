package com.jlas.kaiwa.dto;

import lombok.Builder;
import lombok.Data;

/** Response thông tin kịch bản hội thoại. */
@Data
@Builder
public class ScenarioResponse {
    private Long id;
    private String title;
    private String difficulty;
    private String description;
}
