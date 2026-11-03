package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] What a study reminder can mention: due review cards (every type), whether today is a skill day, days to
 * the exam (null: no exam date), and the items of the level just below the open one still not learned.
 *
 * @param lowerLevelLeft items of {@code lowerLevel} not learned yet (0: nothing to mention)
 * @param lowerLevel     the level just below the open level the learner has a row for; null when there is none
 */
public record JpathReminderFacts(int dueCount, boolean skillDay, Integer daysToExam, int lowerLevelLeft,
                                 String lowerLevel) {
}
