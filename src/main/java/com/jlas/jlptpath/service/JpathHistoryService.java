package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathBadgeView;
import com.jlas.jlptpath.dto.JpathDayDetailView;
import com.jlas.jlptpath.dto.JpathHeatmapView;
import com.jlas.jlptpath.dto.JpathHistoryView;
import com.jlas.jlptpath.dto.JpathStatsView;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] History, stats, heatmap and badges of the JLPT path. Read only (no path lock), only the learner's own
 * data; every day is the learner's local study day ({@link JpathClock}).
 */
public interface JpathHistoryService {

    /** Longest history range in days. */
    int MAX_HISTORY_DAYS = 62;

    /** Default history range in days ({@code [today − 13, today]}). */
    int DEFAULT_HISTORY_DAYS = 14;

    /** Heatmap months when none (or an invalid number) is given; the value is clamped to 1..12. */
    int DEFAULT_HEATMAP_MONTHS = 6;

    /**
     * Days of {@code [from, to]} with activity or a plan, newest first. A missing {@code to} is today, a missing
     * {@code from} is {@code to − 13}.
     *
     * @throws com.jlas.common.exception.ApiException VALIDATION_FAILED when {@code from > to} or the range is longer
     *         than {@link #MAX_HISTORY_DAYS} days
     */
    JpathHistoryView history(Long userId, LocalDate from, LocalDate to);

    /** Items, checks and wrong items of one day. */
    JpathDayDetailView day(Long userId, LocalDate day);

    /**
     * Stats of the last {@code range} days; also refreshes the learner's own streak.
     *
     * @throws com.jlas.common.exception.ApiException VALIDATION_FAILED unless {@code range} is 7 or 30
     */
    JpathStatsView stats(Long userId, int range);

    /** Heatmap from the first day of the month {@code months − 1} months back to today ({@code months} in 1..12). */
    JpathHeatmapView heatmap(Long userId, Integer months);

    /** The badge catalog with the learner's progress. */
    List<JpathBadgeView> badges(Long userId);

    /** Heatmap shade of {@code count} study actions: 0 → 0, 1–9 → 1, 10–24 → 2, 25–49 → 3, ≥ 50 → 4. */
    static int heatLevel(int count) {
        if (count <= 0) {
            return 0;
        }
        if (count < 10) {
            return 1;
        }
        if (count < 25) {
            return 2;
        }
        if (count < 50) {
            return 3;
        }
        return 4;
    }
}
