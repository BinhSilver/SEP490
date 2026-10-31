package com.jlas.learning.dto;

import java.util.List;

/**
 * §2.32 (owner 2026-10-26) — up to 3 extra Reading/Listening passages for one learner beside a chapter's required
 * activity. Never scored, unlocked, rewarded or counted toward completion. {@code items} empty → {@code message} says why.
 */
public record ExtraPracticeSuggestions(Long activityId, String skill, Integer chapter, List<Item> items, String message,
        boolean focus) {
    public ExtraPracticeSuggestions {
        items = List.copyOf(items);
    }

    /** {@code focus} (F1-5, owner 2026-10-28): the path was made to focus on this skill — more passages, shown up front. */
    public ExtraPracticeSuggestions(Long activityId, String skill, Integer chapter, List<Item> items, String message) {
        this(activityId, skill, chapter, items, message, false);
    }

    /**
     * @param passage  opaque key to start this passage ({@code POST …/extra-practice})
     * @param reasons  WEAKNESS (shares refs with the chapter's latest wrong answers), DIFFICULTY (at the target level),
     *                 SAME_CHAPTER
     * @param label    "Nội dung bổ sung J-LAS · cần rà soát" for a DRAFT or J-LAS supplement passage, else null
     */
    public record Item(String passage, int chapterHint, int difficulty, int minutes, int questionCount,
            List<String> reasons, String label) {
        public Item {
            reasons = List.copyOf(reasons);
        }
    }
}
