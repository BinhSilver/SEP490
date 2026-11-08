package com.jlas.learning.dto;

import java.util.List;

/**
 * Owner 2026-10-25: what a Smart Test result asks before its path is created. {@code askKana} asks whether the learner
 * already reads Hiragana/Katakana; {@code choiceKind} (INCOMPLETE_FOUNDATION, MISTAKE_REVIEW, WEAK_LEVEL) is the start
 * question about {@code level}, answered with one of {@code choices}. {@code partial} is true when the test was ended
 * before every skill finished. Owner 2026-10-27 (R1): a WEAK_LEVEL result carries the {@code notice} the learner confirms.
 */
public record LearningEntryQuestions(boolean askKana, String choiceKind, String level, List<String> choices,
        String targetLevel, boolean partial, LearningEntryNotice notice) {
    public LearningEntryQuestions {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public LearningEntryQuestions(boolean askKana, String choiceKind, String level, List<String> choices,
            String targetLevel, boolean partial) {
        this(askKana, choiceKind, level, choices, targetLevel, partial, null);
    }
}
