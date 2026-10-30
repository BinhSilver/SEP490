package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import java.util.List;

/** [JLAS-W0193] Task 6 — server-derived Grammar study state: 1-3 approved points, each with a component
 * breakdown, canonical example, and ordering + fill practice. No flip/remember card here (Grammar's
 * prerequisite gate is the two practice activities, not a flashcard); {@code checkEligible} mirrors
 * Vocabulary/Kanji's {@code checkEligible} but is driven by {@code gateSatisfiedCount == totalPoints}
 * instead of a remembered-card count. */
public record GrammarStudyState(
        Long activityId,
        LearningActivityType activityType,
        LearningActivityState activityState,
        String sourceRevision,
        int totalPoints,
        int gateSatisfiedCount,
        boolean checkEligible,
        boolean checkRequired,
        List<GrammarPointView> points,
        /** [JLAS-W0125] "Phần x / n" among Grammar parts of the package; 1/1 when not split. */
        int partIndex,
        int partCount
) {
    public GrammarStudyState {
        points = List.copyOf(points);
    }
    public GrammarStudyState(Long activityId, LearningActivityType activityType, LearningActivityState activityState,
            String sourceRevision, int totalPoints, int gateSatisfiedCount, boolean checkEligible,
            List<GrammarPointView> points) {
        this(activityId, activityType, activityState, sourceRevision, totalPoints, gateSatisfiedCount, checkEligible,
                true, points, 1, 1);
    }

    /** Only fields with real, approved {@code grammar_point} data are ever populated. {@code usageNote} and
     * {@code note} are {@code null} (never fabricated) when the canonical row has nothing after the first
     * line of {@code meaning}, or no {@code note} at all. */
    public record GrammarPointView(
            Long pointId,
            int orderIndex,
            String pattern,
            String objective,
            String usageNote,
            List<ComponentToken> components,
            ExampleView example,
            String note,
            OrderingPractice ordering,
            FillPractice fill,
            boolean gateSatisfied,
            /** The canonical example is held for review: no example or practice is shown for this point. */
            boolean exampleUnderReview
    ) {
        public GrammarPointView {
            components = List.copyOf(components);
        }
    }

    /** One token of the pattern's structural breakdown (e.g. {@code A / は / B / です}), classified by a
     * fixed, reviewed particle/copula dictionary — {@code role} is one of PARTICLE, AUXILIARY, SLOT, OTHER. */
    public record ComponentToken(String text, String role) { }

    public record ExampleView(String japanese, String meaningVi) { }

    /** {@code words} are shown in a deterministic shuffled order (stable across reloads); {@code tokenId}
     * is the word's position in the CORRECT sentence, never revealed as "correct" until submit. */
    public record OrderingPractice(List<OrderingWord> words, boolean completed) {
        public OrderingPractice {
            words = List.copyOf(words);
        }
    }

    public record OrderingWord(int tokenId, String text) { }

    /** {@code promptWithBlank} never contains the missing word — only the surrounding sentence with a
     * placeholder marker. {@code answer} is only sent once the learner has filled it (or in a read-only review), so a
     * finished point shows the completed sentence (owner 2026-10-21). */
    public record FillPractice(String promptWithBlank, boolean completed, String answer) {
        public FillPractice(String promptWithBlank, boolean completed) { this(promptWithBlank, completed, null); }
    }
}
