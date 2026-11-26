package com.jlas.jlptpath.dto;

import java.time.LocalDate;

/**
 * [JLAS-W0108] F237: today's automatic raise of new words after missed study days, for the path page card.
 * {@code active} = today's words are raised ({@code words} instead of the chosen {@code chosenWords});
 * {@code off} = the learner turned the raise off; {@code milestone} = the exam date ({@code examMilestone}) or the
 * finish day first projected; {@code reachable} false = even the raise finishes later, on {@code finish}.
 * {@code message} is the card text (null when not active).
 */
public record JpathAutoPaceView(boolean active, boolean off, int missedDays, int chosenWords, int words,
        LocalDate milestone, boolean examMilestone, boolean reachable, LocalDate finish, String level,
        String message) {

    public static JpathAutoPaceView none(boolean off) {
        return new JpathAutoPaceView(false, off, 0, 0, 0, null, false, true, null, null, null);
    }
}
