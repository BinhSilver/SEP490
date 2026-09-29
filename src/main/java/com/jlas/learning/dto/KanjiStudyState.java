package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import java.util.List;

/** [JLAS-W0193] Server-derived Kanji study state: membership, flip/remember evidence and eligibility. Mirrors
 * {@link VocabularyStudyState}'s contract (Task 4) with Kanji-appropriate card fields — Task 5 reuses the same
 * state machine via {@code InteractiveStudyEngine}, only the displayed content differs. */
public record KanjiStudyState(
        Long activityId,
        LearningActivityType activityType,
        LearningActivityState activityState,
        String sourceRevision,
        int totalCards,
        int flippedCount,
        int rememberedCount,
        int reviewRemaining,
        boolean checkEligible,
        List<KanjiStudyCard> cards
) {
    public KanjiStudyState {
        cards = List.copyOf(cards);
    }

    /** Only fields with real, approved data are ever populated — {@code onReadings}/{@code kunReadings} are
     * omitted (empty list) rather than fabricated when {@code kanji_reading} has no row of that type. */
    public record KanjiStudyCard(
            Long memberId,
            int orderIndex,
            String character,
            String hanViet,
            String meaningVi,
            String jlptLevel,
            Integer strokeCount,
            List<String> onReadings,
            List<String> kunReadings,
            boolean flipped,
            boolean remembered
    ) {
        public KanjiStudyCard {
            onReadings = List.copyOf(onReadings);
            kunReadings = List.copyOf(kunReadings);
        }
    }
}
