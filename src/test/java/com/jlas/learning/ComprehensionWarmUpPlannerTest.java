package com.jlas.learning;

import com.jlas.learning.dto.SkillScore;
import com.jlas.learning.service.impl.ComprehensionWarmUpPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** [JLAS-W0193] Task 9 — Reading/Listening warm-up banding (design spec §5.4) and daily packing. */
class ComprehensionWarmUpPlannerTest {

    // ---- banding boundaries: p > 70 => 0, (50,70] => 4, (40,50] => 6, [0,40] => 8 ----

    @Test void exactly100PercentBandsToZero() {
        assertBand("READING", 10, 0, 0, false);
    }

    @Test void exactly71PercentBandsToZero() {
        // 71/100 = 71% > 70%.
        assertBand("READING", 100, 29, 0, false);
    }

    @Test void exactly70PercentBandsToFour() {
        // Boundary rule: p > 70 -> 0; p == 70 falls into the (50,70] tier -> 4.
        assertBand("READING", 10, 3, 4, false);
    }

    @Test void exactly50PercentBandsToSix() {
        // p == 50 falls into the (40,50] tier -> 6, not the (50,70] tier.
        assertBand("LISTENING", 10, 5, 6, false);
    }

    @Test void exactly40PercentBandsToEight() {
        // p == 40 falls into the [0,40] tier -> 8, not the (40,50] tier.
        assertBand("LISTENING", 10, 6, 8, false);
    }

    @Test void zeroPercentBandsToEight() {
        assertBand("READING", 10, 10, 8, false);
    }

    @Test void missingEvidenceNeedsVerificationNotZeroPercent() {
        var band = ComprehensionWarmUpPlanner.band(new SkillScore("READING", 0, 0));
        assertTrue(band.needsVerification());
        assertEquals(0, band.lessonCount());
        // Never silently treated as 0% correct (which would also band to 8) -- verification is a distinct
        // outcome from "proven weak".
    }

    private void assertBand(String skill, int assessedCount, int wrongCount, int expectedCount, boolean expectVerification) {
        var band = ComprehensionWarmUpPlanner.band(new SkillScore(skill, assessedCount, wrongCount));
        assertEquals(expectedCount, band.lessonCount());
        assertEquals(expectVerification, band.needsVerification());
        assertEquals(skill, band.skill());
    }

    // ---- daily packing: <=2 combined per day, respecting the minutes-per-day budget ----

    @Test void packingNeverExceedsTwoCombinedPerDay() {
        var days = ComprehensionWarmUpPlanner.packIntoDays(8, 8, 2, 0, 15);
        assertTrue(days.stream().allMatch(d -> d.total() <= 2));
        assertEquals(16, days.stream().mapToInt(ComprehensionWarmUpPlanner.DayAllocation::total).sum());
    }

    @Test void packingInterleavesReadingAndListeningWithinTheCap() {
        var days = ComprehensionWarmUpPlanner.packIntoDays(4, 4, 2, 0, 15);
        assertEquals(4, days.size());
        assertTrue(days.stream().allMatch(d -> d.readingCount() == 1 && d.listeningCount() == 1));
    }

    @Test void packingRespectsMinutesPerDayBudgetEvenBelowTheTwoCap() {
        // Budget only fits one 15-minute lesson per day even though the hard cap is 2.
        var days = ComprehensionWarmUpPlanner.packIntoDays(3, 0, 2, 15, 15);
        assertEquals(3, days.size());
        assertTrue(days.stream().allMatch(d -> d.total() == 1));
    }

    @Test void aSingleLessonLongerThanTheBudgetStillTakesItsOwnDay() {
        // Matches existing V1 precedent (rules doc §2.4): an oversized single resource is not split.
        var days = ComprehensionWarmUpPlanner.packIntoDays(1, 0, 2, 5, 15);
        assertEquals(1, days.size());
        assertEquals(1, days.getFirst().total());
    }

    @Test void zeroCountsProduceNoDays() {
        assertEquals(List.of(), ComprehensionWarmUpPlanner.packIntoDays(0, 0, 2, 30, 15));
    }

    @Test void packingIsDeterministicAcrossRepeatedCalls() {
        var first = ComprehensionWarmUpPlanner.packIntoDays(6, 4, 2, 30, 15);
        var second = ComprehensionWarmUpPlanner.packIntoDays(6, 4, 2, 30, 15);
        assertEquals(first, second);
    }

    @Test void rejectsNonPositiveCapOrMinutesPerLesson() {
        assertThrows(IllegalArgumentException.class, () -> ComprehensionWarmUpPlanner.packIntoDays(1, 1, 0, 30, 15));
        assertThrows(IllegalArgumentException.class, () -> ComprehensionWarmUpPlanner.packIntoDays(1, 1, 2, 30, 0));
    }
}
