package com.jlas.learning.service.impl;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [JLAS-W0108] Phép tính streak thuần túy — cộng ngày liên tiếp, giữ chuỗi dài nhất. */
class LearningStreakTest {

    @Test void startsNewStreakWhenNoPriorCompletion() {
        var s = LearningServiceImpl.advanceStreak(null, null, LocalDate.of(2026, 9, 27));
        assertEquals(1, s.current());
        assertEquals(1, s.longest());
        assertTrue(s.changed());
    }

    @Test void incrementsOnConsecutiveDay() {
        var s = LearningServiceImpl.advanceStreak(3, LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27));
        assertEquals(4, s.current());
        assertEquals(4, s.longest());
        assertTrue(s.changed());
    }

    @Test void sameDayDoesNotDoubleCount() {
        var s = LearningServiceImpl.advanceStreak(3, LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 27));
        assertEquals(3, s.current());
        assertFalse(s.changed());
    }

    @Test void gapResetsStreakButKeepsLongest() {
        var s = LearningServiceImpl.advanceStreak(5, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 27));
        assertEquals(1, s.current());
        assertEquals(5, s.longest());
        assertTrue(s.changed());
    }

    @Test void nullCurrentStreakStartsAtOne() {
        var s = LearningServiceImpl.advanceStreak(null, LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27));
        assertEquals(1, s.current());
        assertEquals(1, s.longest());
    }
}
