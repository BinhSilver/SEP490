package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathDayKind;
import java.time.LocalDate;
import java.util.List;

/**
 * [JLAS-W0108] Summary of the learner's day ("Xong hôm nay"): counts of today's study events, CP paid today
 * ({@code jpath_daily_plan.cp_awarded}), streak and the level % before today and now.
 */
public record JpathDaySummaryView(LocalDate day, JpathDayKind kind, boolean allDone, String level, int newLearned,
                                  int reviewed, Integer accuracyPercent, int cpToday, int streak,
                                  int levelPercentBefore, int levelPercentAfter, List<JpathWrongItemView> wrongItems) {
}
