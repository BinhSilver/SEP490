package com.jlas.jlptpath.dto;

/** [JLAS-W0108] One kana of the kana table; state NEW|LEARNING|NOTEBOOK|KNOWN, audio falls back to TTS. */
public record JpathKanaCellView(String code, String kana, String romaji, String state, String audioUrl, String svgUrl) {
}
