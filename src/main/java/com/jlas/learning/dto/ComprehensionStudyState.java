package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;

import java.util.List;

/** {@code sourceNote}: source and review label for owner-opened unreviewed material (OD-44), null otherwise. */
public record ComprehensionStudyState(Long activityId, LearningActivityType activityType,
        LearningActivityState activityState, String sourceRevision, int exerciseCount, int currentExercise,
        ComprehensionExerciseSource source, List<ComprehensionStudyQuestion> questions,
        MicroCheckStateResponse microCheck, String sourceNote) {
    public ComprehensionStudyState { questions = List.copyOf(questions); }

    public ComprehensionStudyState(Long activityId, LearningActivityType activityType,
            LearningActivityState activityState, String sourceRevision, int exerciseCount, int currentExercise,
            ComprehensionExerciseSource source, List<ComprehensionStudyQuestion> questions,
            MicroCheckStateResponse microCheck) {
        this(activityId, activityType, activityState, sourceRevision, exerciseCount, currentExercise, source, questions,
                microCheck, null);
    }
}
