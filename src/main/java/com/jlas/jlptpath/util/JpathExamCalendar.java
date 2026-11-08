package com.jlas.jlptpath.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;

/** [JLAS-W0108] JLPT sessions: the first Sunday of July and of December. */
public final class JpathExamCalendar {

    private JpathExamCalendar() {
    }

    /** First JLPT session day on or after {@code day}. */
    public static LocalDate nextSessionOnOrAfter(LocalDate day) {
        for (int year = day.getYear(); ; year++) {
            for (Month month : new Month[] {Month.JULY, Month.DECEMBER}) {
                LocalDate session = LocalDate.of(year, month, 1)
                        .with(TemporalAdjusters.firstInMonth(DayOfWeek.SUNDAY));
                if (!session.isBefore(day)) {
                    return session;
                }
            }
        }
    }
}
