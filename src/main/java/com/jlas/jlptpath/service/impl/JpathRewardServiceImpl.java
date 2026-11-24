package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathBadgeService;
import com.jlas.jlptpath.service.JpathRewardService;
import com.jlas.jlptpath.util.JpathRewardPolicy;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.mission.service.DailyGoalService;
import com.jlas.mission.service.StreakService;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [JLAS-W0108] Pays the JLPT path rewards inside the caller's transaction: CP is queued with {@link CreditAwardService}
 * (the ledger's reference key keeps a repeat from paying twice), the streak goes through {@link StreakService} and
 * badges through {@link JpathBadgeService}. Takes no jpath lock (the callers hold the path lock). A transaction that
 * queued CP applies the learner's open awards once, after commit.
 *
 * <p>Keep in sync: the pay conditions here (SKILL tab never, eligible only, REST day ignored, SKILL_DAY vs DAY) must
 * match the {@code cp +=} lines of {@code JpathDayCompletion.evaluate}, which report the CP shown on the done screen
 * and stored in {@code jpath_daily_plan.cp_awarded}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JpathRewardServiceImpl implements JpathRewardService {

    /** Transaction resource marking that the after-commit apply is already registered. */
    private static final Object APPLY_KEY = JpathRewardServiceImpl.class;

    /** Transaction resource marking that the after-commit daily-goal check is already registered (F128). */
    static final Object DAILY_GOAL_KEY = DailyGoalService.class.getName() + "#jpath";

    private final CreditAwardService awards;
    private final StreakService streaks;
    private final JpathBadgeService badges;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTabDayCompleted(Long userId, LocalDate day, JpathTab tab, boolean rewardEligible) {
        // owner 25/11: no CP for a part of the day; the day lump is paid in onDayCompleted
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReviewsCleared(Long userId, LocalDate day) {
        // owner 25/11: paid with the whole day
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onDayCompleted(Long userId, LocalDate day, JpathDayKind kind, boolean rewardEligible) {
        if (kind == JpathDayKind.REST) {
            return;
        }
        streaks.recordCompletion(userId, day);
        // A streak milestone may queue CP inside recordCompletion, even on a day that is not reward eligible.
        applyAfterCommit(userId);
        // owner 25/11: a weekly check passed while the day was still open is paid with the day, in one gift
        int owed = takeOwedWeekCp(userId, day);
        String with = owed > 0 ? " (kèm kiểm tra tuần)" : "";
        if (rewardEligible) {
            if (kind == JpathDayKind.SKILL) {
                pay(userId, JpathRewardPolicy.dayTotal(kind) + owed, JpathRewardPolicy.REF_SKILL_DAY, day.toEpochDay(),
                        "Xong ngày kỹ năng " + day.format(DAY_MONTH) + with);
            } else {
                pay(userId, JpathRewardPolicy.dayTotal(kind) + owed, JpathRewardPolicy.REF_DAY, day.toEpochDay(),
                        "Xong ngày học " + day.format(DAY_MONTH) + with);
            }
        } else if (owed > 0) {
            pay(userId, owed, JpathRewardPolicy.REF_WEEK_CHECK, day.toEpochDay(), "Đạt kiểm tra tuần " + day.format(DAY_MONTH));
        }
        evaluateBadges(userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSkillSessionDone(Long userId, LocalDate day, JpathSkill skill) {
        // owner 25/11: the two sessions are paid with the skill day, not one by one
    }

    @Override
    public void onWeeklyCheckPassed(Long userId, LocalDate day, JpathTab tab) {
        if (jdbc != null && jdbc.update("UPDATE jpath_daily_plan SET week_cp_owed = week_cp_owed + ? WHERE user_id = ? "
                + "AND study_day = ? AND day_kind <> 'REST' AND all_done_at IS NULL", JpathRewardPolicy.WEEK_CHECK, userId, day) > 0) {
            return; // paid with today's lump (onDayCompleted)
        }
        pay(userId, JpathRewardPolicy.WEEK_CHECK, JpathRewardPolicy.REF_WEEK_CHECK,
                JpathRewardPolicy.weekCheckRef(day, tab), "Đạt kiểm tra tuần " + label(tab));
    }

    private static final java.time.format.DateTimeFormatter DAY_MONTH = java.time.format.DateTimeFormatter.ofPattern("dd/MM");

    /** Optional: without it a weekly check is paid at once, as before. */
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired(required = false)
    void setJdbc(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private int takeOwedWeekCp(Long userId, LocalDate day) {
        if (jdbc == null) return 0;
        java.util.List<Integer> owed = jdbc.queryForList("SELECT week_cp_owed FROM jpath_daily_plan WHERE user_id = ? AND study_day = ? FOR UPDATE",
                Integer.class, userId, day);
        int n = owed.isEmpty() || owed.get(0) == null ? 0 : owed.get(0);
        if (n > 0) jdbc.update("UPDATE jpath_daily_plan SET week_cp_owed = 0 WHERE user_id = ? AND study_day = ?", userId, day);
        return n;
    }

    /** Optional: without the mission daily goal the path pays as before. */
    private DailyGoalService dailyGoal;

    @Autowired(required = false)
    void setDailyGoal(DailyGoalService dailyGoal) {
        this.dailyGoal = dailyGoal;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onProgressChanged(Long userId) {
        evaluateBadges(userId);
        refreshDailyGoalAfterCommit(userId);
    }

    /**
     * [JLAS-W0108] F128: the learner's own daily goal counts the path study (new items, checks, minutes) read from the
     * committed rows, so it is checked after commit, once per transaction, in its own transaction. A goal met there
     * queues its reward, which is applied right away; a failure is logged and the path step stays saved.
     */
    private void refreshDailyGoalAfterCommit(Long userId) {
        DailyGoalService goal = dailyGoal;
        if (goal == null || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getResource(DAILY_GOAL_KEY) != null) {
            return;
        }
        TransactionSynchronizationManager.bindResource(DAILY_GOAL_KEY, userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (goal.refreshToday(userId)) {
                        awards.applyOpenAwards(userId);
                    }
                } catch (RuntimeException ex) {
                    log.error("Could not re-check the daily goal for user {}", userId, ex);
                }
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(DAILY_GOAL_KEY);
            }
        });
    }

    private void evaluateBadges(Long userId) {
        if (!badges.evaluate(userId).isEmpty()) {
            applyAfterCommit(userId);
        }
    }

    private void pay(Long userId, int amount, String refType, long refId, String note) {
        awards.enqueue(userId, amount, CreditReason.STUDY_REWARD, refType, refId, note, null, null, null);
        applyAfterCommit(userId);
    }

    /** Registers one after-commit {@code applyOpenAwards} per transaction; without synchronization nothing happens. */
    private void applyAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getResource(APPLY_KEY) != null) {
            return;
        }
        TransactionSynchronizationManager.bindResource(APPLY_KEY, userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    awards.applyOpenAwards(userId);
                } catch (RuntimeException ex) {
                    // The request already committed; the retry job applies what is still open.
                    log.error("Could not apply open credit awards for user {}", userId, ex);
                }
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(APPLY_KEY);
            }
        });
    }

    private static String label(JpathTab tab) {
        return switch (tab) {
            case VOCAB -> "Từ vựng";
            case KANJI -> "Kanji";
            case GRAMMAR -> "Ngữ pháp";
            case KANA -> "Chữ cái";
            case SKILL -> "Kỹ năng";
        };
    }
}
