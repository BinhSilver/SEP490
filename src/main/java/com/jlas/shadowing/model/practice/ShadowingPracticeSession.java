package com.jlas.shadowing.model.practice;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ShadowingPracticeSession {
    private Long id;
    private Long userId;
    private Long videoId;
    private PracticeMode mode;
    private PracticeOrder order;
    private int requestedCount;
    private int totalQuestions;
    private int currentPosition;
    private int correctCount;
    private int scoreTotal;
    private long randomSeed;
    private PracticeSessionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
