package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathAutoPaceView;
import com.jlas.jlptpath.dto.JpathPaceInput;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathTodayTargetsPort;
import com.jlas.jlptpath.util.JpathAutoPaceRule;
import com.jlas.jlptpath.util.JpathDays;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] F237 (owner 23/11): today's words raised after missed study days ({@link JpathAutoPaceRule}), the card
 * of the path page and its "Hoàn tác". The raise is decided when today's plan is made (or its targets refreshed) and
 * kept on the plan, so the card and the day's targets agree all day.
 */
@Component
public class JpathAutoPaceService {

    /** Missed study days are counted over the last four weeks. */
    static final int MISSED_WINDOW_DAYS = 28;
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    /** A raise for one day: the rule's result, the balanced V/K/G of the raised words and the missed days. */
    public record Boost(JpathAutoPaceRule.Raise raise, JpathSliderValues sliders, int missedDays) {
    }

    private final JpathPaceService paceService;
    private final JpathCalendarService calendar;
    private final JpathDailyPlanRepository plans;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathClock clock;
    private final ObjectProvider<JpathTodayTargetsPort> todayTargets;

    public JpathAutoPaceService(JpathPaceService paceService, JpathCalendarService calendar,
            JpathDailyPlanRepository plans, JpathUserPathRepository paths, JpathUserLevelRepository levels,
            JpathClock clock, ObjectProvider<JpathTodayTargetsPort> todayTargets) {
        this.paceService = paceService;
        this.calendar = calendar;
        this.plans = plans;
        this.paths = paths;
        this.levels = levels;
        this.clock = clock;
        this.todayTargets = todayTargets;
    }

    /**
     * The raise for the plan of {@code day} (a STUDY day outside the kana stage), under the caller's path → level
     * locks, or null. A path without exam date gets its milestone projected here the first time on a level (stored on
     * the path).
     */
    public Boost decide(JpathUserPath path, JpathUserLevel level, LocalDate day) {
        if (path.isAutoPaceOff()) {
            return null;
        }
        // [JLAS-W0108] Pace changes now wait for a completed week and learner consent. A missed
        // study day must not trigger F237's automatic increase while unfinished work carries over.
        if (missedDays(path, level, day) > 0) {
            return null;
        }
        JpathPaceInput in = paceService.engineInput(path.getUserId(), query(path, level, day));
        if (in.examDate() == null && (path.getPaceMilestone() == null
                || !level.getLevel().equals(path.getPaceMilestoneLevel()))) {
            path.setPaceMilestone(JpathAutoPaceRule.plannedFinish(in));
            path.setPaceMilestoneLevel(level.getLevel());
        }
        int missed = missedDays(path, level, day);
        JpathAutoPaceRule.Raise raise = JpathAutoPaceRule.decide(in,
                in.examDate() == null ? path.getPaceMilestone() : null, missed);
        if (raise == null) {
            return null;
        }
        JpathSliderValues sliders = paceService.balanced(path.getUserId(), level.getLevel(), raise.words());
        return new Boost(raise, sliders, missed);
    }

    /** Decides the raise for {@code plan} and stores it there; returns the sliders its tabs use. */
    JpathSliderValues applyTo(JpathDailyPlan plan, JpathUserPath path, JpathUserLevel level) {
        Boost boost = plan.getDayKind() == JpathDayKind.STUDY && !plan.isKanaPhase()
                ? decide(path, level, plan.getStudyDay()) : null;
        plan.setAutoWords(boost == null ? null : boost.raise().words());
        plan.setAutoFinish(boost == null || boost.raise().reachable() ? null : boost.raise().finish());
        return boost == null ? chosen(level) : boost.sliders();
    }

    static JpathSliderValues chosen(JpathUserLevel level) {
        return new JpathSliderValues(level.getSliderVocab(), level.getSliderKanji(), level.getSliderGrammar());
    }

    /**
     * Study days of the calendar in the last {@link #MISSED_WINDOW_DAYS} days before {@code day} (from the path's
     * start) left unfinished: no plan, or a study plan not done.
     */
    int missedDays(JpathUserPath path, JpathUserLevel level, LocalDate day) {
        LocalDate from = day.minusDays(MISSED_WINDOW_DAYS);
        if (path.getCreatedAt() != null && path.getCreatedAt().toLocalDate().isAfter(from)) {
            from = path.getCreatedAt().toLocalDate();
        }
        if (!from.isBefore(day)) {
            return 0;
        }
        Map<LocalDate, JpathDailyPlan> byDay = new HashMap<>();
        plans.findByUserIdAndStudyDayBetween(path.getUserId(), from, day.minusDays(1))
                .forEach(p -> byDay.put(p.getStudyDay(), p));
        int missed = 0;
        for (LocalDate d = from; d.isBefore(day); d = d.plusDays(1)) {
            if (calendar.dayKind(path, level.getStage(), d) != JpathDayKind.STUDY) {
                continue;
            }
            JpathDailyPlan p = byDay.get(d);
            if (p == null || p.getDayKind() == JpathDayKind.STUDY && p.getAllDoneAt() == null) {
                missed++;
            }
        }
        return missed;
    }

    /** The card of today: active when today's plan carries a raise and the learner has not turned it off. */
    @Transactional(readOnly = true)
    public JpathAutoPaceView view(Long userId) {
        Optional<JpathUserPath> active = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE);
        if (active.isEmpty()) {
            return JpathAutoPaceView.none(false);
        }
        JpathUserPath path = active.get();
        if (path.isAutoPaceOff()) {
            return JpathAutoPaceView.none(true);
        }
        LocalDate today = clock.today(userId);
        Optional<JpathDailyPlan> plan = plans.findByUserIdAndStudyDay(userId, today);
        Optional<JpathUserLevel> level = levels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()));
        if (plan.isEmpty() || plan.get().getAutoWords() == null || level.isEmpty()) {
            return JpathAutoPaceView.none(false);
        }
        JpathDailyPlan p = plan.get();
        int chosen = level.get().getSliderVocab();
        if (p.getAutoWords() <= chosen) {
            return JpathAutoPaceView.none(false);
        }
        LocalDate exam = JpathPaceService.usableExamDate(path.getExamDate(), today);
        LocalDate milestone = exam != null ? exam : path.getPaceMilestone();
        boolean reachable = p.getAutoFinish() == null;
        int missed = Math.max(1, missedDays(path, level.get(), today));
        String finishLevel = exam != null ? path.getTargetLevel() : path.getOpenLevel();
        return new JpathAutoPaceView(true, false, missed, chosen, p.getAutoWords(), milestone, exam != null,
                reachable, p.getAutoFinish(), finishLevel,
                message(missed, p.getAutoWords(), chosen, milestone, exam != null, p.getAutoFinish(), finishLevel));
    }

    /** Card text in the learner's words. */
    static String message(int missed, int words, int chosen, LocalDate milestone, boolean exam, LocalDate later,
            String level) {
        String head = "Bạn đã lỡ " + missed + " ngày học, hôm nay tăng lên " + words + " từ";
        String mark = milestone == null ? "" : (exam ? "ngày thi " : "ngày xong dự kiến ") + DAY_MONTH.format(milestone);
        if (later == null) {
            return head + (mark.isEmpty() ? "." : " để kịp " + mark + ".")
                    + " Khi bắt kịp, mỗi ngày trở lại " + chosen + " từ như bạn chọn.";
        }
        return head + ". Dù vậy bạn sẽ xong " + level + " khoảng " + DAY_MONTH.format(later)
                + (mark.isEmpty() ? "." : ", sau " + mark + ".");
    }

    /**
     * "Hoàn tác": turns the raise off for the learner (chosen words per day kept) and puts today's tabs not started
     * yet back on the chosen words. No active path → JPATH_NO_ACTIVE_PATH.
     */
    @Transactional
    public JpathAutoPaceView turnOff(Long userId) {
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        path.setAutoPaceOff(true);
        paths.flush();
        JpathTodayTargetsPort port = todayTargets.getIfAvailable();
        if (port != null) {
            port.refreshTodayTargets(userId);
        }
        return JpathAutoPaceView.none(true);
    }

    private static JpathPaceService.Query query(JpathUserPath path, JpathUserLevel level, LocalDate day) {
        return new JpathPaceService.Query(day, path.getOpenLevel(), path.getTargetLevel(), path.getExamDate(),
                path.getDailyMinutes() == null ? 30 : path.getDailyMinutes(), JpathDays.parse(path.getSkillDays()),
                JpathDays.parse(path.getRestDays()), JpathPathViewAssembler.storedThirdDay(path), chosen(level), 0,
                path.getStudyMode() == null ? JpathStudyMode.COMBINED : path.getStudyMode(),
                level.getSkillPracticeAt() == null ? null : level.getSkillPracticeAt().toLocalDate(), false, null);
    }
}
