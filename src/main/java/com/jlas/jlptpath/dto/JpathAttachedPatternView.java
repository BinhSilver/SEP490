package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] A secondary grammar pattern shown inside the lesson of the main pattern it is attached to: its pattern,
 * meaning and first example (any of them may be null). It is not learned on its own.
 */
public record JpathAttachedPatternView(String code, String pattern, String meaning, String exampleJa,
                                       String exampleFurigana, String exampleVi) {
}
