package com.jlas.dictionary.dto;

public record InlineLookupResponse(Long vocabularyId, String expression, String reading,
                                   String meaning, String wordType, String detailUrl) { }
