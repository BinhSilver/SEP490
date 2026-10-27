package com.jlas.dictionary.dto;

/** {@code saved}: the signed-in learner already has this word in their favorites (false when signed out). */
public record InlineLookupResponse(Long vocabularyId, String expression, String reading,
                                   String meaning, String wordType, String jlptLevel,
                                   String exampleJa, String exampleVi, String detailUrl, boolean saved) { }
