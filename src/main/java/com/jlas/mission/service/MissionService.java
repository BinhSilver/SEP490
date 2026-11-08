package com.jlas.mission.service;

import com.jlas.mission.config.MissionClock;
import com.jlas.mission.dto.CelebrationResponse;
import com.jlas.mission.dto.DailyGoalProgress;
import com.jlas.mission.dto.MissionDashboardResponse;
import com.jlas.mission.dto.MissionSummaryResponse;
import com.jlas.mission.dto.StreakResponse;
import com.jlas.mission.repository.RewardCelebrationRepository;
import com.jlas.moderation.credit.service.UserCreditService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the mission module: dashboard, summary widget and pending celebrations. */
@Service
@RequiredArgsConstructor
public class MissionService {

    private static final int CELEBRATION_BATCH = 30;

    private final DailyGoalService dailyGoal;
    private final DailyMissionService missions;
    private final StreakService streaks;
    private final CreditAwardService awards;
    private final UserCreditService credits;
    private final RewardCelebrationRepository celebrations;
    private final UserZoneService zones;
    private final MissionClock clock;

    public MissionDashboardResponse dashboard(Long userId) {
        // Apply anything still queued first, so the balance shown already includes it.
        awards.applyOpenAwards(userId);
        ZoneId zone = zones.zoneOf(userId);
        LocalDate today = clock.today(zone);
        StreakResponse streak = streaks.refresh(userId);
        UserCreditService.EarnCapStatus cap = credits.earnCapStatus(userId);
        return new MissionDashboardResponse(today, zone.getId(), credits.balance(userId),
                dailyGoal.progress(userId, today), streak, missions.list(userId, today), awards.openAwards(userId),
                cap.earnedToday(), cap.dailyCap());
    }

    public MissionSummaryResponse summary(Long userId) {
        LocalDate today = clock.today(zones.zoneOf(userId));
        StreakResponse streak = streaks.refresh(userId);
        DailyGoalProgress goal = dailyGoal.progress(userId, today);
        return new MissionSummaryResponse(credits.balance(userId), streak.currentStreak(), streak.longestStreak(), goal,
                streak.week());
    }

    @Transactional(readOnly = true)
    public List<CelebrationResponse> celebrations(Long userId) {
        return celebrations.findUnseen(userId, CELEBRATION_BATCH);
    }

    @Transactional
    public int acknowledge(Long userId, List<Long> ids) {
        return celebrations.markSeen(userId, ids);
    }
}
