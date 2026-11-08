package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathSessionTab;
import java.util.List;

/**
 * [JLAS-W0108] A study session: status OPEN, COMPLETED or ABANDONED; {@code position} = index of the current step
 * (= {@code total} when finished, then {@code current} is null); {@code steps} = the session's segments in queue order
 * (key REVIEW, KANA, VOCAB, KANJI, GRAMMAR or SKILL_QUIZ, done = steps before {@code position});
 * {@code minutesLeft} = estimate of the steps not done yet; {@code upcoming} = at most 20 steps after the current one.
 */
public record JpathSessionView(Long sessionId, JpathSessionTab tab, JpathSessionMode mode, String status, int position,
                               int total, int minutesLeft, List<JpathCombinedStep> steps, JpathCardView current,
                               List<JpathCardView> upcoming) {
}
