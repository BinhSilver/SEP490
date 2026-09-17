package com.jlas.mission.service.impl;

import com.jlas.mission.dto.MissionSummaryResponse;
import com.jlas.mission.dto.StreakSnapshot;
import com.jlas.mission.service.CreditLedgerService;
import com.jlas.mission.service.DailyGoalService;
import com.jlas.mission.service.MissionService;
import com.jlas.mission.service.StreakService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements {@link MissionService}. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionServiceImpl implements MissionService {

    private final DailyGoalService dailyGoal;
    private final CreditLedgerService creditLedger;
    private final StreakService streaks;

    @Override
    public MissionSummaryResponse summary(Long userId) {
        StreakSnapshot streak = streaks.snapshot(userId);
        return new MissionSummaryResponse(
                creditLedger.balance(userId),
                streak.currentStreak(),
                streak.longestStreak(),
                dailyGoal.summary(userId));
    }
}
