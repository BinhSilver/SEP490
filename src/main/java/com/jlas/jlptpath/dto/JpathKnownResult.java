package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Result of "Tôi biết từ này": {@code state} KNOWN, or the item stays NEW and {@code checkToken} names
 * the one-question check to take first.
 */
public record JpathKnownResult(String state, String checkToken) {
}
