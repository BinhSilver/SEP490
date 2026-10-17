package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import java.util.List;

/** [JLAS-W0193] Server-derived vocabulary study state: membership, flip/remember evidence and eligibility. */
public record VocabularyStudyState(
        Long activityId,
        LearningActivityType activityType,
        LearningActivityState activityState,
        String sourceRevision,
        int totalCards,
        int flippedCount,
        int rememberedCount,
        int reviewRemaining,
        boolean checkEligible,
        boolean checkRequired,
        List<VocabularyStudyCard> cards,
        /** [JLAS-W0125] "Phần x / n" among same-skill parts of the package; 1/1 when not split. */
        int partIndex,
        int partCount
) {
    public VocabularyStudyState {
        cards = List.copyOf(cards);
    }
    public VocabularyStudyState(Long activityId, LearningActivityType activityType, LearningActivityState activityState,
            String sourceRevision, int totalCards, int flippedCount, int rememberedCount, int reviewRemaining,
            boolean checkEligible, List<VocabularyStudyCard> cards) {
        this(activityId, activityType, activityState, sourceRevision, totalCards, flippedCount, rememberedCount,
                reviewRemaining, checkEligible, true, cards, 1, 1);
    }

    public record VocabularyStudyCard(
            Long memberId,
            int orderIndex,
            String front,
            String reading,
            String meaning,
            String wordType,
            String jlptLevel,
            String exampleJa,
            String exampleVi,
            boolean flipped,
            boolean remembered
    ) { }
}
