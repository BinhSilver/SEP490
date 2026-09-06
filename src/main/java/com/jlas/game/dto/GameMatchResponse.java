package com.jlas.game.dto;

import lombok.Builder;
import lombok.Data;

/** Response thông tin một trận game. */
@Data
@Builder
public class GameMatchResponse {
    private Long id;
    private String gameType;
    private String status;
}
