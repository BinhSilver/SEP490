package com.jlas.learning.dto;

import java.util.List;

/** [JLAS-W0108] Owner 2026-10-21: optional extra practice for one grammar pattern — new sentences written by the AI
 * provider, each turned into an ordering and a fill-blank exercise. Never scored, never part of completion; the
 * learner chooses whether to do them, so the parts arrive in order and the page shuffles and checks them itself. */
public record GrammarExtraPractice(String label, List<Item> items) {
    public GrammarExtraPractice {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** {@code parts}: the sentence's chunks in the correct order; {@code prompt}: the sentence with the blank. */
    public record Item(String japanese, String meaningVi, List<String> parts, boolean spaced, String prompt, String answer) {
        public Item {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }
}
