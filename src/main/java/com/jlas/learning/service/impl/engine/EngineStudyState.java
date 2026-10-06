package com.jlas.learning.service.impl.engine;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;

import java.util.List;

/** [JLAS-W0193] Content-agnostic study state produced by {@link InteractiveStudyEngine}; each concrete
 * service (Vocabulary, Kanji, ...) zips {@link EngineCard#entity()} into its own content-specific DTO. */
public record EngineStudyState<T>(
        Long activityId,
        LearningActivityType activityType,
        LearningActivityState activityState,
        String sourceRevision,
        int totalCards,
        int flippedCount,
        int rememberedCount,
        int reviewRemaining,
        boolean checkEligible,
        List<EngineCard<T>> cards,
        /** [JLAS-W0125] Position among same-skill parts of the package; 1/1 when the skill is not split. */
        int partIndex,
        int partCount
) {
    public EngineStudyState {
        cards = List.copyOf(cards);
    }

    public record EngineCard<T>(Long memberId, int orderIndex, T entity, boolean flipped, boolean remembered) { }
}
