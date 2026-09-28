package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;

public record InteractiveLearningActivityResponse(
        Long id,
        LearningActivityType type,
        LearningActivityState state,
        int orderIndex) {
}
