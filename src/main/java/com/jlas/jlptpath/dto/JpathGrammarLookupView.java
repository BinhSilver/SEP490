package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] One grammar pattern matched by Look up (plan Task 62): the pattern card with its first MAIN example and
 * the lesson link {@code /learning/jlpt/grammar/{code}}.
 */
public record JpathGrammarLookupView(String code, String pattern, String meaning, String formation, String level,
                                     String exampleJa, String exampleFurigana, String exampleVi, String url) {
}
