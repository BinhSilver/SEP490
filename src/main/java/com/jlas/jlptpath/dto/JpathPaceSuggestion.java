package com.jlas.jlptpath.dto;

import java.time.LocalDate;

/**
 * [JLAS-W0108] Way out of a too-tight target: {@code kind} is MORE_MINUTES (with {@code minutes}), LATER_EXAM (with
 * {@code examDate}) or LOWER_TARGET (with {@code level}); {@code finish} is the expected ready day under it.
 */
public record JpathPaceSuggestion(String kind, Integer minutes, LocalDate examDate, String level, LocalDate finish) {
}
