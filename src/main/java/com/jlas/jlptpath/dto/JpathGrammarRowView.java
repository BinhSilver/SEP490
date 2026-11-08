package com.jlas.jlptpath.dto;

/** [JLAS-W0108] One pattern line of the grammar list; state NEW|LEARNING|KNOWN. */
public record JpathGrammarRowView(String code, String pattern, String meaning, String state, boolean learnable) {
}
