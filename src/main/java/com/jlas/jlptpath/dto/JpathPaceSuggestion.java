package com.jlas.jlptpath.dto;

import java.time.LocalDate;

/**
 * [JLAS-W0108] Way out of a too-tight target: {@code kind} is MORE_WORDS (with {@code words} per day,
 * owner 22/11 F230), LATER_EXAM (with
 * {@code examDate}) or LOWER_TARGET (with {@code level}); {@code finish} is the expected ready day under it.
 */
public record JpathPaceSuggestion(String kind, Integer minutes, LocalDate examDate, String level, LocalDate finish,
        Integer words) {
}
