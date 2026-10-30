package com.jlas.learning.dto;

/** [JLAS-W0193] Task 6 — immediate feedback after an ordering or fill-blank practice submit. {@code
 * correctText} is the canonical (already-approved) sentence/word used to explain a wrong answer — it is
 * never a micro-check answer key, just the same approved content already shown as the point's example. */
public record GrammarPracticeResult(
        Long pointId,
        String practiceType,
        boolean correct,
        boolean gateSatisfied,
        String correctText,
        String explanation
) { }
