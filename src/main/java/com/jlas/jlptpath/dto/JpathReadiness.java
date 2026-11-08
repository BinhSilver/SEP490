package com.jlas.jlptpath.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * [JLAS-W0108] Exam readiness of a level (#41, a suggestion that never locks the Mock button): every visible type done
 * at least once and the last two section practices of each skill passed.
 */
public record JpathReadiness(boolean allTypesDone, int typesDone, int typesTotal, List<SectionTry> lastReading,
                             List<SectionTry> lastListening, boolean ready) {

    /** One submitted section practice. */
    public record SectionTry(LocalDateTime submittedAt, BigDecimal scorePct, boolean passed) {
    }
}
