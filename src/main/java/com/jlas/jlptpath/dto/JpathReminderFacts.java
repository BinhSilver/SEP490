package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] What a study reminder can mention: due review cards (every type), whether today is a skill day, days to
 * the exam (null: no exam date), and the items of the level just below the open one still not learned.
 *
 * @param lowerLevelLeft items of {@code lowerLevel} not learned yet (0: nothing to mention)
 * @param lowerLevel     the level just below the open level the learner has a row for; null when there is none
 * @param readingType    Vietnamese name of today's reading type on a skill day; null when not known
 * @param listeningType  Vietnamese name of today's listening type on a skill day; null when not known
 */
public record JpathReminderFacts(int dueCount, boolean skillDay, Integer daysToExam, int lowerLevelLeft,
                                 String lowerLevel, String readingType, String listeningType) {

    /** Without today's skill types (Plan 3 Task 24 added them; null: not known). */
    public JpathReminderFacts(int dueCount, boolean skillDay, Integer daysToExam, int lowerLevelLeft,
                              String lowerLevel) {
        this(dueCount, skillDay, daysToExam, lowerLevelLeft, lowerLevel, null, null);
    }
}
