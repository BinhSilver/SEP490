package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathBadgeService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathEntryRoute;
import com.jlas.jlptpath.service.JpathEntryRouter;
import com.jlas.jlptpath.service.JpathFeature;
import com.jlas.mission.entity.UserStreaks;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.mission.service.CreditAwardService;
import com.jlas.mission.service.StreakService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] A rest day of the JLPT path does not break the learner's streak: when today (the learner's day) is a
 * REST day and the streak's last completed day is yesterday, today is recorded as completed. Running again on the same
 * day changes nothing (the streak's last day is then today), and two rest days in a row both keep the streak.
 *
 * <p>Each learner gets one short transaction following the daily-plan contract: today's plan is made to exist first
 * (outside any transaction), then the path lock, then the level and plan locks through {@code lockToday}; the kind of
 * today is the one stored in that plan (decided by {@code JpathCalendarService.dayKind} when the plan was made, so a
 * rest-day change made later in the day applies from tomorrow). A kept streak may reach a badge (evaluated in the same
 * transaction) or a streak milestone; the CP they queue is applied right after the commit, and anything that fails
 * there stays queued for the award retry job. A failure for one learner is logged and the job goes on.
 */
@Slf4j
@Component
public class JpathRestDayStreakJob {

    static final int PAGE_SIZE = 500;

    private final JpathFeature feature;
    private final JpathEntryRouter router;
    private final JpathUserPathRepository paths;
    private final JpathDailyPlanService plans;
    private final UserStreaksRepository streakRows;
    private final StreakService streaks;
    private final JpathBadgeService badges;
    private final CreditAwardService awards;
    private final JpathClock clock;
    private final TransactionOperations tx;

    @Autowired
    public JpathRestDayStreakJob(JpathFeature feature, JpathEntryRouter router, JpathUserPathRepository paths,
            JpathDailyPlanService plans, UserStreaksRepository streakRows, StreakService streaks,
            JpathBadgeService badges, CreditAwardService awards, JpathClock clock,
            PlatformTransactionManager transactionManager) {
        this(feature, router, paths, plans, streakRows, streaks, badges, awards, clock,
                new TransactionTemplate(transactionManager));
    }

    JpathRestDayStreakJob(JpathFeature feature, JpathEntryRouter router, JpathUserPathRepository paths,
            JpathDailyPlanService plans, UserStreaksRepository streakRows, StreakService streaks,
            JpathBadgeService badges, CreditAwardService awards, JpathClock clock, TransactionOperations tx) {
        this.feature = feature;
        this.router = router;
        this.paths = paths;
        this.plans = plans;
        this.streakRows = streakRows;
        this.streaks = streaks;
        this.badges = badges;
        this.awards = awards;
        this.clock = clock;
        this.tx = tx;
    }

    /** Walks every ACTIVE path in id order, 500 per page; returns how many streaks were kept. */
    @Scheduled(initialDelay = 150_000, fixedDelay = 900_000)
    public int run() {
        if (!feature.enabled()) {
            return 0;
        }
        int kept = 0;
        long afterId = 0L;
        List<JpathUserPath> page;
        do {
            page = paths.findActiveAfter(afterId, PageRequest.of(0, PAGE_SIZE));
            for (JpathUserPath path : page) {
                if (keepStreak(path.getUserId())) {
                    kept++;
                }
            }
            if (!page.isEmpty()) {
                afterId = page.get(page.size() - 1).getId();
            }
        } while (page.size() == PAGE_SIZE);
        return kept;
    }

    private boolean keepStreak(Long userId) {
        try {
            if (router.route(userId) != JpathEntryRoute.JPATH_PAGE) {
                return false;
            }
            plans.ensurePlanExists(userId);
            boolean kept = Boolean.TRUE.equals(tx.execute(status -> keepStreakLocked(userId)));
            if (kept) {
                applyAwards(userId);
            }
            return kept;
        } catch (RuntimeException e) {
            log.warn("Rest-day streak check failed for user {}: {}", userId, e.toString());
            return false;
        }
    }

    private boolean keepStreakLocked(Long userId) {
        Optional<JpathUserPath> path = paths.findActiveForUpdate(userId);
        if (path.isEmpty()) {
            return false;
        }
        JpathDailyPlan plan = plans.lockToday(path.get());
        LocalDate today = clock.today(userId);
        if (plan.getDayKind() != JpathDayKind.REST || !today.equals(plan.getStudyDay())) {
            return false;
        }
        LocalDate last = streakRows.findByUserId(userId).map(UserStreaks::getLastCompletedDate).orElse(null);
        if (last == null || !last.equals(today.minusDays(1))) {
            return false;
        }
        streaks.recordCompletion(userId, today);
        badges.evaluate(userId);
        return true;
    }

    /** After the commit: applies the CP a milestone or badge queued; on failure the retry job applies it later. */
    private void applyAwards(Long userId) {
        try {
            awards.applyOpenAwards(userId);
        } catch (RuntimeException e) {
            log.error("Could not apply open credit awards for user {}", userId, e);
        }
    }
}
