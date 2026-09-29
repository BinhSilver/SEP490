package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;

import java.util.List;

public record ComprehensionStudyState(Long activityId, LearningActivityType activityType,
        LearningActivityState activityState, String sourceRevision, int exerciseCount, int currentExercise,
        ComprehensionExerciseSource source, List<ComprehensionStudyQuestion> questions,
        MicroCheckStateResponse microCheck) {
    public ComprehensionStudyState { questions = List.copyOf(questions); }
}
