package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] What one evaluation of a daily plan completed; {@code cpExpected} = CP the reward hook pays for it
 * (0 when the hook was not called), already added to {@code jpath_daily_plan.cp_awarded}.
 */
public record JpathCompletionOutcome(boolean tabCompleted, boolean dayCompleted, boolean reviewsCleared,
                                     int cpExpected) {
}
