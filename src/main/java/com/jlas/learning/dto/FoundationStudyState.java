package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import java.util.List;

/** [JLAS-W0108] Server-derived Hiragana/Katakana study state: membership, flip/remember evidence and
 * eligibility. Mirrors {@link KanjiStudyState}'s contract, reusing the same {@code InteractiveStudyEngine}
 * state machine — only the displayed content (a kana character + its romaji) differs. */
public record FoundationStudyState(
        Long activityId,
        LearningActivityType activityType,
        LearningActivityState activityState,
        String sourceRevision,
        int totalCards,
        int flippedCount,
        int rememberedCount,
        int reviewRemaining,
        boolean checkEligible,
        List<FoundationStudyCard> cards
) {
    public FoundationStudyState {
        cards = List.copyOf(cards);
    }

    public record FoundationStudyCard(
            Long memberId,
            int orderIndex,
            String character,
            String romaji,
            boolean flipped,
            boolean remembered
    ) {
    }
}
