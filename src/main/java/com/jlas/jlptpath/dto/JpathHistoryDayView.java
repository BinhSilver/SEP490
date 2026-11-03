package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathDayKind;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] One day of the learner's history: day kind (stored plan, else the calendar; null outside the path),
 * new learned and reviewed items, whether the whole day was done, the CP the day paid ({@code cp_awarded}) and the
 * submitted checks, newest first.
 */
public record JpathHistoryDayView(LocalDate day, JpathDayKind kind, int newLearned, int reviewed, boolean done,
                                  int cpAwarded, List<JpathCheckSummaryView> checks) {
}
