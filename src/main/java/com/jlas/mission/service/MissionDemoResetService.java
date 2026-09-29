package com.jlas.mission.service;

import com.jlas.mission.config.MissionClock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo only ({@code mission.dev-tools=true}): wipes the current learner's mission state so a manual
 * test can start again from a clean day. Credits earned or spent through missions, daily goals,
 * milestones and freezes are removed from the ledger and the balance is corrected by the same amount;
 * credits from other features are left untouched.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mission", name = "dev-tools", havingValue = "true")
public class MissionDemoResetService {

    private static final String MISSION_LEDGER = "user_id = ? AND (reason IN "
            + "('DAILY_GOAL_REWARD', 'STREAK_MILESTONE_REWARD', 'STREAK_FREEZE') OR reference_type = 'USER_MISSION')";

    private final JdbcTemplate jdbc;
    private final MissionClock clock;
    private final CreditAwardWorker worker;

    @Transactional
    public Map<String, Object> reset(Long userId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, userId);
        Long net = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM user_credit_transaction WHERE "
                + MISSION_LEDGER, Long.class, userId);
        int removed = jdbc.update("DELETE FROM user_credit_transaction WHERE " + MISSION_LEDGER, userId);
        jdbc.update("UPDATE users SET credit_balance = GREATEST(0, credit_balance - ?) WHERE id = ?",
                net == null ? 0 : net, userId);
        for (String table : new String[]{"learning_event_receipt", "pending_credit_award", "reward_celebration",
                "streak_freeze_day", "user_mission", "user_daily_progress", "user_streaks", "user_learning_targets"}) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
        }
        jdbc.update("DELETE FROM user_notification WHERE user_id = ? AND type = 'STREAK'", userId);
        clock.shift(null);
        worker.simulateFailure(false);
        Long balance = jdbc.queryForObject("SELECT credit_balance FROM users WHERE id = ?", Long.class, userId);
        return Map.of("ledgerRowsRemoved", removed, "creditsReverted", net == null ? 0 : net,
                "creditBalance", balance == null ? 0 : balance);
    }
}
