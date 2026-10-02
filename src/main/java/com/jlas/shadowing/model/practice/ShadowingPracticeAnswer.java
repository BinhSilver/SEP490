package com.jlas.shadowing.model.practice;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ShadowingPracticeAnswer {
    private Long id;
    private Long practiceItemId;
    private int attemptNumber;
    private String userAnswer;
    private String normalizedAnswer;
    private AnswerResult result;
    private int score;
    private String feedback;
    private LocalDateTime answeredAt;
}
