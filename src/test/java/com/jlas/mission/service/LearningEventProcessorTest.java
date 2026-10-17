package com.jlas.mission.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jlas.common.event.LearningEvent;
import com.jlas.common.event.LearningEventType;
import com.jlas.mission.config.MissionClock;
import com.jlas.mission.enums.MissionCode;
import com.jlas.mission.repository.MissionJdbcRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LearningEventProcessorTest {

    @Mock MissionJdbcRepository jdbc;
    @Mock DailyGoalService dailyGoal;
    @Mock DailyMissionService missions;
    @Mock UserZoneService zones;
    MissionClock clock;
    LearningEventProcessor processor;

    @BeforeEach
    void setUp() {
        clock = new MissionClock(Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC));
        processor = new LearningEventProcessor(jdbc, dailyGoal, missions, zones, clock);
    }

    @Test
    void replayedEventHasNoSecondEffect() {
        LearningEvent event = event("lesson:retry", LearningEventType.LESSON_COMPLETED,
                7L, 12, 8, "2026-10-03T03:00:00Z");
        when(jdbc.recordReceipt(event)).thenReturn(false);

        assertFalse(processor.process(event));

        verifyNoInteractions(dailyGoal, missions, zones);
    }

    @Test
    void occurredAtIsMappedToEachLearnersLocalDayNotProcessingDay() {
        Instant boundary = Instant.parse("2026-10-03T15:30:00Z");
        LearningEvent tokyo = new LearningEvent("lesson:tokyo", LearningEventType.LESSON_COMPLETED,
                7L, 70L, 12, 8, boundary);
        LearningEvent losAngeles = new LearningEvent("lesson:la", LearningEventType.LESSON_COMPLETED,
                8L, 80L, 9, 5, boundary);
        when(jdbc.recordReceipt(tokyo)).thenReturn(true);
        when(jdbc.recordReceipt(losAngeles)).thenReturn(true);
        when(zones.zoneOf(7L)).thenReturn(ZoneId.of("Asia/Tokyo"));
        when(zones.zoneOf(8L)).thenReturn(ZoneId.of("America/Los_Angeles"));

        assertTrue(processor.process(tokyo));
        assertTrue(processor.process(losAngeles));

        verify(dailyGoal).addActivity(7L, LocalDate.of(2026, 10, 4), 8, 0, 12);
        verify(dailyGoal).addActivity(8L, LocalDate.of(2026, 10, 3), 5, 0, 9);
        verify(missions).advance(7L, MissionCode.COMPLETE_LESSON, LocalDate.of(2026, 10, 4), 1);
        verify(missions).advance(8L, MissionCode.COMPLETE_LESSON, LocalDate.of(2026, 10, 3), 1);
    }

    @Test
    void demoOffsetMovesLateEventAndQuizEffectsTogether() {
        clock.shift(Duration.ofDays(1));
        LearningEvent event = event("quiz:late", LearningEventType.LESSON_QUIZ_COMPLETED,
                7L, 0, 0, "2026-10-01T17:30:00Z");
        when(jdbc.recordReceipt(event)).thenReturn(true);
        when(zones.zoneOf(7L)).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));

        assertTrue(processor.process(event));

        LocalDate shiftedDay = LocalDate.of(2026, 10, 3);
        verify(dailyGoal).addActivity(7L, shiftedDay, 0, 1, 0);
        verify(missions).advance(7L, MissionCode.COMPLETE_QUIZ, shiftedDay, 1);
    }

    @Test
    void pathCompletionReceiptDoesNotFabricateDailyGoalOrMissionProgress() {
        LearningEvent event = event("path:done", LearningEventType.PATH_COMPLETED,
                7L, 0, 0, "2026-10-03T03:00:00Z");
        when(jdbc.recordReceipt(event)).thenReturn(true);
        when(zones.zoneOf(7L)).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));

        assertTrue(processor.process(event));

        verify(dailyGoal, never()).addActivity(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(missions);
    }

    private static LearningEvent event(String id, LearningEventType type, Long userId,
            int minutes, int vocabulary, String occurredAt) {
        return new LearningEvent(id, type, userId, 1L, minutes, vocabulary, Instant.parse(occurredAt));
    }
}
