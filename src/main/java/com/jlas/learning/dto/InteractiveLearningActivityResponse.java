package com.jlas.learning.dto;

import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;

public record InteractiveLearningActivityResponse(
        Long id,
        LearningActivityType type,
        LearningActivityState state,
        int orderIndex,
        /** [JLAS-W0193] Task 9 — provenance for Warm-up B activities ("vì sao tôi học nội dung này"),
         * e.g. {@code WEAK_VOCAB_ITEM}/{@code WEAK_KANJI_ITEM}/{@code WEAK_GRAMMAR_POINT}. Null for
         * GOAL_LEARNING activities, which are composed from published curriculum, not weakness evidence. */
        String reasonCode,
        String reason) {
}
