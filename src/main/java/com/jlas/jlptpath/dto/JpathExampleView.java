package com.jlas.jlptpath.dto;

/** [JLAS-W0108] One example sentence of a card or item (role MAIN or EXTRA); audioUrl null when there is no file. */
public record JpathExampleView(String code, String role, String ja, String jaFurigana, String vi, String audioUrl) {
}
