package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathCompletionOutcome;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathRewardHook;
import com.jlas.jlptpath.util.JpathRewardPolicy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Tab and day completion of a daily plan (policy #24, #25, plan decision 33). Writes the marks
 * ({@code done_at}, {@code reviews_cleared_at}, {@code all_done_at}) on any day, but calls the {@link JpathRewardHook}
 * only for today's plan, and adds the CP it pays to {@code cp_awarded}.
 */
@Component
public class JpathDayCompletion {

    private final JpathDailyPlanService planService;
    private final JpathUserItemRepository userItems;
    private final ObjectProvider<JpathRewardHook> hooks;
    private final JpathClock clock;

    public JpathDayCompletion(JpathDailyPlanService planService, JpathUserItemRepository userItems, ObjectProvider<JpathRewardHook> hooks, JpathClock clock) {
        this.planService = planService;
        this.userItems = userItems;
        this.hooks = hooks;
        this.clock = clock;
    }

    /**
     * Evaluates {@code plan} after an event on {@code touchedTab} (null: no tab, only reviews and the day). Runs
     * under the caller's locks (path → level → plan → tabs, taken in the writer transaction after the plan was made
     * to exist outside it): {@code plan} and {@code tabs} are the locked, managed rows with the event's counters
     * already written. Neither is re-read here and no lock is taken.
     * <ul>
     *   <li>Tab done: {@code new_done >= effectiveTarget} and (target 0 or {@code check_passed_at}); SKILL: its
     *       {@code new_done} (DAY skill sessions) reaches its {@code new_target}. The hook is told only for V/K/G/KANA
     *       with an effective target &gt; 0.</li>
     *   <li>Any other non-SKILL tab not done whose effective target is 0 (its content ran out) is marked done
     *       without hook or CP, so it never blocks the day.</li>
     *   <li>Reviews cleared: every tab has {@code review_done >= review_cap} or no due card, and at least one review
     *       was done today.</li>
     *   <li>Day done: STUDY → every required tab done; SKILL → reviews done (or none due) and the SKILL tab done;
     *       REVIEW_ONLY → reviews done (or none due); REST → never. The day reward needs {@code rewardEligible} and
     *       no tab of the plan {@code too_fast}.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JpathCompletionOutcome evaluate(Long userId, JpathDailyPlan plan, List<JpathDailyTab> lockedTabs,
            JpathTab touchedTab, boolean rewardEligible) {
        return run(userId, plan, lockedTabs, touchedTab, rewardEligible, true);
    }

    /**
     * The same marks for a plan that was just inserted or switched (the inserter's transaction, under its path, level
     * and plan locks, owning the plan and tabs), never calling the hook and paying no CP: a day with nothing to do
     * (SKILL quiz without a source and no card due, or no new item left on any required tab) is done at once, so it
     * counts as done for the owed skill days (#14) and never earns CP or streak by itself.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JpathCompletionOutcome settleWithoutReward(Long userId, JpathDailyPlan plan,
            List<JpathDailyTab> lockedTabs) {
        return run(userId, plan, lockedTabs, null, false, false);
    }

    private JpathCompletionOutcome run(Long userId, JpathDailyPlan plan, List<JpathDailyTab> lockedTabs,
            JpathTab touchedTab, boolean rewardEligible, boolean mayReward) {
        LocalDateTime now = clock.now();
        LocalDate day = plan.getStudyDay();
        List<JpathDailyTab> rows = lockedTabs.stream().filter(t -> plan.getId().equals(t.getPlanId())).toList();
        Map<JpathTab, JpathDailyTab> tabs = new EnumMap<>(JpathTab.class);
        rows.forEach(t -> tabs.put(t.getTab(), t));

        boolean tabCompleted = false;
        boolean tabPays = false;
        JpathDailyTab touched = touchedTab == null ? null : tabs.get(touchedTab);
        if (touched != null && touched.getDoneAt() == null) {
            boolean checked = touched.getCheckPassedAt() != null;
            boolean skill = touched.getTab() == JpathTab.SKILL;
            int target = skill ? touched.getNewTarget() : planService.effectiveTarget(plan, touched);
            // Plan 3 Task 16: the SKILL tab is done by its DAY sessions (one per skill); it pays no tab CP (#44 pays
            // each skill session apart)
            boolean complete = skill ? touched.getNewDone() >= target
                    : touched.getNewDone() >= target && (target == 0 || checked);
            if (complete) {
                touched.setDoneAt(now);
                tabCompleted = true;
                tabPays = !skill && target > 0;
            }
        }

        for (JpathDailyTab t : rows) {
            int target = t.getTab() == JpathTab.SKILL ? t.getNewTarget() : planService.effectiveTarget(plan, t);
            if (t != touched && t.getDoneAt() == null && target == 0) {
                t.setDoneAt(now);
            }
        }

        boolean reviewsDone = reviewsDone(userId, rows);
        boolean reviewsCleared = false;
        if (plan.getReviewsClearedAt() == null && reviewsDone
                && rows.stream().mapToInt(JpathDailyTab::getReviewDone).sum() >= 1) {
            plan.setReviewsClearedAt(now);
            reviewsCleared = true;
        }

        boolean dayCompleted = false;
        if (plan.getAllDoneAt() == null && dayDone(plan, tabs, reviewsDone || plan.getReviewsClearedAt() != null)) {
            plan.setAllDoneAt(now);
            dayCompleted = true;
        }
        boolean dayEligible = rewardEligible && rows.stream().noneMatch(JpathDailyTab::isTooFast);

        int cp = 0;
        JpathRewardHook hook = mayReward && day.equals(clock.today(userId)) ? hooks.getIfAvailable() : null;
        if (hook != null) {
            if (tabPays) {
                hook.onTabDayCompleted(userId, day, touched.getTab(), rewardEligible);
                cp += rewardEligible ? JpathRewardPolicy.TAB_DAY : 0;
            }
            if (reviewsCleared) {
                hook.onReviewsCleared(userId, day);
                cp += JpathRewardPolicy.REVIEW_DAY;
            }
            if (dayCompleted) {
                hook.onDayCompleted(userId, day, plan.getDayKind(), dayEligible);
                if (dayEligible) {
                    cp += plan.getDayKind() == JpathDayKind.SKILL ? JpathRewardPolicy.SKILL_DAY : JpathRewardPolicy.DAY;
                }
            }
            hook.onProgressChanged(userId);
            plan.setCpAwarded(plan.getCpAwarded() + cp);
        }
        return new JpathCompletionOutcome(tabCompleted, dayCompleted, reviewsCleared, cp);
    }

    /** Every V/K/G/KANA tab has reached its review cap or has no card due now. */
    private boolean reviewsDone(Long userId, List<JpathDailyTab> rows) {
        LocalDateTime localNow = clock.localNow(userId);
        for (JpathDailyTab t : rows) {
            if (t.getTab() == JpathTab.SKILL || t.getReviewDone() >= t.getReviewCap()) {
                continue;
            }
            if (userItems.countDue(userId, JpathItemType.valueOf(t.getTab().name()), localNow) > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean dayDone(JpathDailyPlan plan, Map<JpathTab, JpathDailyTab> tabs, boolean reviewsDone) {
        return switch (plan.getDayKind()) {
            // A required tab without a row has nothing to do.
            case STUDY -> planService.requiredTabs(plan).stream()
                    .allMatch(t -> !tabs.containsKey(t) || tabs.get(t).getDoneAt() != null);
            // Plan 3 Task 16: reviews done and the SKILL tab done (both skills); no SKILL row → reviews only
            case SKILL -> reviewsDone && (!tabs.containsKey(JpathTab.SKILL)
                    || tabs.get(JpathTab.SKILL).getDoneAt() != null);
            case REVIEW_ONLY -> reviewsDone;
            case REST -> false;
        };
    }
}
