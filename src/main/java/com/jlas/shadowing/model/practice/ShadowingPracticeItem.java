package com.jlas.shadowing.model.practice;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ShadowingPracticeItem {
    private Long id;
    private Long sessionId;
    private Long subtitleId;
    private Long clozeCandidateId;
    private int position;
    private int difficultyScore;
    private LocalDateTime answeredAt;
}
