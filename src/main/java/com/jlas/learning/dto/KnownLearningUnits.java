package com.jlas.learning.dto;

import java.util.Set;

/**
 * F1-4 (owner 2026-10-28): words ({@code VOCABULARY_ENTRY} ids) and Kanji the learner already proved — passed in an
 * earlier path, or (words) answered right in a full Smart Test batch. A new path leaves them out of its required part and
 * lists them as "Đã vững". Grammar stays required (owner 2026-10-27: grammar is always CORE).
 */
public record KnownLearningUnits(Set<Long> vocabularyIds, Set<Long> kanjiIds) {
    public static final KnownLearningUnits NONE = new KnownLearningUnits(Set.of(), Set.of());

    public KnownLearningUnits {
        vocabularyIds = vocabularyIds == null ? Set.of() : Set.copyOf(vocabularyIds);
        kanjiIds = kanjiIds == null ? Set.of() : Set.copyOf(kanjiIds);
    }

    public boolean isEmpty() {
        return vocabularyIds.isEmpty() && kanjiIds.isEmpty();
    }
}
