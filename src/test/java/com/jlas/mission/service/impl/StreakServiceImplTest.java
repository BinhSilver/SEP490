package com.jlas.mission.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jlas.mission.config.MissionClock;
import com.jlas.mission.config.MissionProperties;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.enums.FreezeDayStatus;
import com.jlas.mission.repository.MissionJdbcRepository;
import com.jlas.mission.repository.RewardCelebrationRepository;
import com.jlas.mission.repository.StreakFreezeDayRepository;
import com.jlas.mission.repository.StreakFreezeDayRepository.FreezeDay;
import com.jlas.mission.repository.UserDailyProgressRepository;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.mission.service.DailyMissionService;
import com.jlas.mission.service.UserZoneService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.notification.service.UserNotificationPort;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StreakServiceImplTest {

    private static final long USER_ID = 7L;
    private static final MissionProperties PROPERTIES =
            new MissionProperties("Asia/Ho_Chi_Minh", 10, 50, 2, 7, 60_000, false);

    @Mock UserStreaksRepository streaks;
    @Mock StreakFreezeDayRepository freezeDays;
    @Mock UserDailyProgressRepository dailyProgress;
    @Mock MissionJdbcRepository jdbc;
    @Mock RewardCelebrationRepository celebrations;
    @Mock DailyMissionService missions;
    @Mock CreditAwardService awards;
    @Mock UserCreditService credits;
    @Mock UserNotificationPort notifications;
    @Mock UserZoneService zones;
    MissionClock clock;
    StreakServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new MissionClock(Clock.fixed(Instant.parse("2026-03-29T10:00:00Z"), ZoneOffset.UTC));
        service = new StreakServiceImpl(streaks, freezeDays, dailyProgress, jdbc, celebrations,
                missions, awards, credits, notifications, zones, clock, PROPERTIES);
        when(missions.activeStreakMilestones()).thenReturn(List.of());
        when(dailyProgress.findByUserIdAndProgressDateBetween(any(), any(), any())).thenReturn(List.of());
        when(freezeDays.findUsedBetween(any(), any(), any())).thenReturn(Set.of());
    }

    @Test
    void retryAfterPurchasedFreezeWasAlreadyUsedDoesNotChargeOrDecideAgain() {
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        LocalDate today = LocalDate.of(2026, 3, 29);
        LocalDate missed = today.minusDays(1);
        UserStreaks streak = UserStreaks.builder().userId(USER_ID).currentStreak(5).longestStreak(5)
                .lastCompletedDate(today).freezeCount(0).build();
        FreezeDay used = new FreezeDay(missed, FreezeDayStatus.USED, 4, "PURCHASE");
        when(zones.zoneOf(USER_ID)).thenReturn(zone);
        when(streaks.findForUpdate(USER_ID)).thenReturn(Optional.of(streak));
        when(freezeDays.find(USER_ID, missed)).thenReturn(Optional.of(used));
        when(freezeDays.lock(USER_ID, missed)).thenReturn(Optional.of(used));
        when(credits.balance(USER_ID)).thenReturn(150L);

        var response = service.useFreeze(USER_ID, true);

        assertEquals(0, response.creditsSpent());
        assertEquals(150L, response.creditBalance());
        assertEquals(5, response.streak().currentStreak());
        verify(credits, never()).spend(any(), any(Integer.class), any(CreditReason.class),
                any(), any(), any());
        verify(freezeDays, never()).decide(any(), any(), any(), any());
    }

    @Test
    void freezeDeadlineUsesNextLocalMidnightAcrossDstSpringForward() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        LocalDate today = LocalDate.of(2026, 3, 29);
        LocalDate missed = today.minusDays(1);
        UserStreaks streak = UserStreaks.builder().userId(USER_ID).currentStreak(6).longestStreak(6)
                .lastCompletedDate(today.minusDays(2)).freezeCount(1).build();
        FreezeDay offered = new FreezeDay(missed, FreezeDayStatus.OFFERED, 6, null);
        when(zones.zoneOf(USER_ID)).thenReturn(berlin);
        when(streaks.findForUpdate(USER_ID)).thenReturn(Optional.of(streak));
        when(freezeDays.offer(USER_ID, missed, 6)).thenReturn(false);
        when(freezeDays.find(USER_ID, missed)).thenReturn(Optional.of(offered));
        when(credits.balance(USER_ID)).thenReturn(100L);

        var response = service.refresh(USER_ID);

        assertEquals("AT_RISK", response.status());
        assertNotNull(response.freezeOffer());
        assertEquals(missed, response.freezeOffer().missedDate());
        assertEquals(Instant.parse("2026-03-29T22:00:00Z"), response.freezeOffer().deadline());
        assertEquals(50, response.freezeOffer().price());
    }
}
