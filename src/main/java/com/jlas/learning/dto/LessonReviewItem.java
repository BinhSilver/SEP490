package com.jlas.learning.dto;

/**
 * [JLAS-W0108] OD-D2-05: a word or Kanji of this chapter that the learner already studied (and was checked on) in an
 * earlier chapter of the same path. Shown as "Ôn — đã học ở …"; not a card, not part of this chapter's check.
 */
public record LessonReviewItem(Long canonicalId, String front, String reading, String meaning, String learnedIn) { }
