package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] Stats of the last {@code range} days (7 or 30). {@code accuracy}: share of right check answers per tab
 * in the range (null when the tab has none). {@code learned} / {@code known} / {@code notebook}: the learner's items
 * per tab on every level now (counted towards progress, KNOWN, NOTEBOOK). {@code days}: every day, oldest first.
 */
public record JpathStatsView(int range, Map<JpathTab, Double> accuracy, Map<JpathTab, Long> learned,
                             Map<JpathTab, Long> known, Map<JpathTab, Long> notebook, int currentStreak,
                             int longestStreak, List<JpathStatsDayView> days, List<JpathBadgeView> badges) {
}
