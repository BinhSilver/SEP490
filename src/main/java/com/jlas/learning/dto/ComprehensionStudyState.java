package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;

import java.util.List;

/** {@code sourceNote}: source and review label for owner-opened unreviewed material (OD-44), null otherwise.
 * {@code passageTranslations}: per exercise (batch order), the source's line-by-line Vietnamese translation of a reading
 * passage; only sent once the part is passed, so it never helps answer the scored check. Empty otherwise. */
public record ComprehensionStudyState(Long activityId, LearningActivityType activityType,
        LearningActivityState activityState, String sourceRevision, int exerciseCount, int currentExercise,
        ComprehensionExerciseSource source, List<ComprehensionStudyQuestion> questions,
        MicroCheckStateResponse microCheck, String sourceNote, List<List<TranslationLine>> passageTranslations) {
    public ComprehensionStudyState {
        questions = List.copyOf(questions);
        passageTranslations = passageTranslations == null ? List.of() : List.copyOf(passageTranslations);
    }

    public ComprehensionStudyState(Long activityId, LearningActivityType activityType,
            LearningActivityState activityState, String sourceRevision, int exerciseCount, int currentExercise,
            ComprehensionExerciseSource source, List<ComprehensionStudyQuestion> questions,
            MicroCheckStateResponse microCheck) {
        this(activityId, activityType, activityState, sourceRevision, exerciseCount, currentExercise, source, questions,
                microCheck, null, List.of());
    }

    public record TranslationLine(String ja, String vi) { }
}
