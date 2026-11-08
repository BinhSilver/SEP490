package com.jlas.jlptpath.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * [JLAS-W0108] System admin report of the JLPT path. {@code learnersByLevel}: ACTIVE paths per open level (N5..N1, 0
 * when none). Percentages have 2 decimals and are null when there is nothing to measure. {@code mockAttempts} and
 * {@code mockPassed} stay 0 until the mock test is wired in (Plan 3). {@code legacyActivePaths}: ACTIVE paths of the
 * older learning path.
 */
public record JpathAdminReportView(LocalDateTime generatedAt, Map<String, Long> learnersByLevel, long activeLearners7d,
                                   Double dayCompletionPercent30d, Double averageStreak,
                                   Double microCheckPassPercent30d, long mockAttempts, long mockPassed,
                                   long reportsOpen, long reportsTotal, long legacyActivePaths) {
}
