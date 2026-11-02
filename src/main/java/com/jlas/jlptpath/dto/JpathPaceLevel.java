package com.jlas.jlptpath.dto;

import java.time.LocalDate;

/**
 * [JLAS-W0108] One level of a pace estimate: item totals and counted items per tab, plus the day skill practice
 * started (null when it has not started).
 */
public record JpathPaceLevel(String level, int vocabTotal, int kanjiTotal, int grammarTotal, int vocabDone,
        int kanjiDone, int grammarDone, LocalDate skillPracticeSince) {
}
