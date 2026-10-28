package com.jlas.learning.dto;

import java.util.List;

/**
 * Owner 2026-10-25: what a Smart Test result asks before its path is created. {@code askKana} asks whether the learner
 * already reads Hiragana/Katakana; {@code choiceKind} (INCOMPLETE_FOUNDATION, INTRO_LEVEL, MISTAKE_REVIEW) is the start
 * question about {@code level}, answered with one of {@code choices}. {@code partial} is true when the test was ended
 * before every skill finished.
 */
public record LearningEntryQuestions(boolean askKana, String choiceKind, String level, List<String> choices,
        String targetLevel, boolean partial) {
    public LearningEntryQuestions {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
