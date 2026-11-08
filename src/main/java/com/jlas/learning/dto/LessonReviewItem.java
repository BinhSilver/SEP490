package com.jlas.learning.dto;

/**
 * [JLAS-W0108] OD-D2-05: a word or Kanji of this chapter that the learner already studied (and was checked on) in an
 * earlier chapter of the same path. Shown as "Ôn — đã học ở …"; not a card, not part of this chapter's check.
 */
public record LessonReviewItem(Long canonicalId, String front, String reading, String meaning, String learnedIn,
        boolean known) {
    /**
     * F1-4 (owner 2026-10-28): {@code known} marks a word/Kanji the learner already proved (passed in an earlier path or
     * answered right in a full Smart Test batch), left out of this path's required part — shown as "Đã vững".
     */
    public LessonReviewItem(Long canonicalId, String front, String reading, String meaning, String learnedIn) {
        this(canonicalId, front, reading, meaning, learnedIn, false);
    }
}
