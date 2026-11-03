package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDateTime;

/**
 * [JLAS-W0108] One submitted check of a history day: score, pass flag, server submit time and whether the learner
 * can take it again ({@code redoable}: micro-check, free quiz, confusables and the grammar practice kinds).
 */
public record JpathCheckSummaryView(String token, JpathCheckKind kind, JpathTab tab, Double scorePercent,
                                    Boolean passed, LocalDateTime submittedAt, boolean redoable) {
}
