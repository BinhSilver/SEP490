package com.jlas.learning.service.impl;

import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Plans source-bound Reading/Listening checks independently from flashcard and curriculum member sizing. */
public final class ComprehensionWorkloadPolicy {
    private static final int MINUTES_PER_EXERCISE = 3;
    private static final int ATOMIC_AUDIO_MINUTES = 15;

    public LearningWorkloadPolicy.ActivityPlan plan(LearningActivityType type, List<Long> questionIds) {
        if (type != LearningActivityType.READING && type != LearningActivityType.LISTENING) {
            throw new IllegalArgumentException("Comprehension activities must be READING or LISTENING.");
        }
        if (questionIds == null || questionIds.isEmpty() || questionIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A comprehension activity needs canonical question IDs.");
        }
        List<Long> distinct = List.copyOf(new LinkedHashSet<>(questionIds));
        return new LearningWorkloadPolicy.ActivityPlan(type, LearningMemberType.COMPREHENSION_QUESTION,
                distinct, Math.multiplyExact(distinct.size(), MINUTES_PER_EXERCISE));
    }

    /** A source audio clip stays together with every question belonging to that clip. */
    public LearningWorkloadPolicy.ActivityPlan planAudioClip(List<Long> questionIds) {
        var validated = plan(LearningActivityType.LISTENING, questionIds);
        return new LearningWorkloadPolicy.ActivityPlan(LearningActivityType.LISTENING,
                LearningMemberType.COMPREHENSION_QUESTION, validated.memberIds(), ATOMIC_AUDIO_MINUTES);
    }
}
