package com.jlas.shadowing.model.practice;

import java.util.List;

/** Original subtitle IDs remain authoritative; contextSummary is never answer evidence. */
public record QuestionEvidencePlan(String kind, List<Long> sourceIds, String focus,
                                   String answerQuote, List<Long> contextIds, String contextSummary) {
}
