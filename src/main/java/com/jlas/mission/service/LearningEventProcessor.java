package com.jlas.mission.service;

import com.jlas.common.event.LearningEvent;
import com.jlas.mission.config.MissionClock;
import com.jlas.mission.enums.MissionCode;
import com.jlas.mission.repository.MissionJdbcRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one learning event to daily goal, streak and missions.
 *
 * <p>The event id is recorded in the same transaction as its effects, so a replayed event changes
 * nothing and a failed one leaves nothing half-applied.
 */
@Service
@RequiredArgsConstructor
public class LearningEventProcessor {

    private final MissionJdbcRepository jdbc;
    private final DailyGoalService dailyGoal;
    private final DailyMissionService missions;
    private final UserZoneService zones;
    private final MissionClock clock;

    /** Returns false when the event was already processed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean process(LearningEvent event) {
        if (!jdbc.recordReceipt(event)) {
            return false;
        }
        // The demo clock offset is applied to event time too, so "tomorrow" in a demo stays consistent.
        LocalDate day = LocalDate.ofInstant(event.occurredAt().plus(clock.offset()), zones.zoneOf(event.userId()));
        switch (event.type()) {
            case LESSON_STARTED -> {
                // Recorded for the contract; starting a lesson earns nothing by itself.
            }
            case LESSON_PROGRESS_UPDATED -> dailyGoal.addActivity(event.userId(), day, 0, 0, event.minutes());
            case LESSON_COMPLETED -> dailyGoal.addActivity(event.userId(), day,
                    event.vocabularyCount(), 0, event.minutes());
            case LESSON_QUIZ_COMPLETED -> dailyGoal.addActivity(event.userId(), day, 0, 1, 0);
            case SHADOWING_PUBLISHED -> {
                // Publishing a video advances its mission only; it is not vocabulary study.
            }
        }
        MissionCode.triggeredBy(event.type())
                .ifPresent(code -> missions.advance(event.userId(), code, day, 1));
        return true;
    }
}
