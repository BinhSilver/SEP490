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
        List<VocabularyStudyCard> cards
) {
    public VocabularyStudyState {
        cards = List.copyOf(cards);
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
