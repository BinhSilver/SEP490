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
        List<KanjiStudyCard> cards,
        /** [JLAS-W0125] "Phần x / n" among same-skill parts of the package; 1/1 when not split. */
        int partIndex,
        int partCount,
        /** Owner 2026-10-21 (OD-13): credit line for the English meanings shown on the cards; null when none is shown. */
        String meaningAttribution,
        /** OD-D2-05: Kanji of this chapter already learnt in an earlier one ("Ôn"); never cards, never checked. */
        List<LessonReviewItem> reviewItems
) {
    public KanjiStudyState {
        cards = List.copyOf(cards);
        reviewItems = reviewItems == null ? List.of() : List.copyOf(reviewItems);
    }
    public KanjiStudyState(Long activityId, LearningActivityType activityType, LearningActivityState activityState,
            String sourceRevision, int totalCards, int flippedCount, int rememberedCount, int reviewRemaining,
            boolean checkEligible, List<KanjiStudyCard> cards, int partIndex, int partCount, String meaningAttribution) {
        this(activityId, activityType, activityState, sourceRevision, totalCards, flippedCount, rememberedCount,
                reviewRemaining, checkEligible, cards, partIndex, partCount, meaningAttribution, List.of());
    }
    public KanjiStudyState(Long activityId, LearningActivityType activityType, LearningActivityState activityState,
            String sourceRevision, int totalCards, int flippedCount, int rememberedCount, int reviewRemaining,
            boolean checkEligible, List<KanjiStudyCard> cards, int partIndex, int partCount) {
        this(activityId, activityType, activityState, sourceRevision, totalCards, flippedCount, rememberedCount,
                reviewRemaining, checkEligible, cards, partIndex, partCount, null);
    }
    public KanjiStudyState(Long activityId, LearningActivityType activityType, LearningActivityState activityState,
            String sourceRevision, int totalCards, int flippedCount, int rememberedCount, int reviewRemaining,
            boolean checkEligible, List<KanjiStudyCard> cards) {
        this(activityId, activityType, activityState, sourceRevision, totalCards, flippedCount, rememberedCount,
                reviewRemaining, checkEligible, cards, 1, 1);
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
            boolean remembered,
            /** KANJIDIC2 English meanings ("one; one radical (no.1)"), shown with the Hán Việt (OD-13). */
            String meaningEn
    ) {
        public KanjiStudyCard {
            onReadings = List.copyOf(onReadings);
            kunReadings = List.copyOf(kunReadings);
        }
        public KanjiStudyCard(Long memberId, int orderIndex, String character, String hanViet, String meaningVi,
                String jlptLevel, Integer strokeCount, List<String> onReadings, List<String> kunReadings,
                boolean flipped, boolean remembered) {
            this(memberId, orderIndex, character, hanViet, meaningVi, jlptLevel, strokeCount, onReadings, kunReadings,
                    flipped, remembered, null);
        }
    }
}
