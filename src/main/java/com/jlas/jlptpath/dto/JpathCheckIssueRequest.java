package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathCheckSource;
import com.jlas.jlptpath.enums.JpathQuizDirection;
import com.jlas.jlptpath.enums.JpathTab;
import jakarta.validation.constraints.NotNull;

/**
 * [JLAS-W0108] Request to issue a check. Session-bound kinds (MICRO_CHECK, GROUP_EXERCISE, KANA_ROW) need
 * {@code sessionId}; the server then takes the kind and tab from the session's current step. {@code timerOn} null =
 * the learner's saved timer setting. The stored copy ({@code issue_json}) is the public echo of this request.
 */
public record JpathCheckIssueRequest(@NotNull JpathCheckKind kind, @NotNull JpathTab tab, Long sessionId, String level,
                                     String topicKey, Integer unitNo, JpathCheckSource source, Integer count,
                                     JpathQuizDirection direction, Boolean timerOn, String itemCode, String redoOf) {
}
