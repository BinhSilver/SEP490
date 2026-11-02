package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.jlptpath.dto.JpathCalendarDayView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserLevelId;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathUserLevelRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.util.JpathDays;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS-W0108] Week calendar and third skill day (plan decision 14). */
@Service
public class JpathCalendarServiceImpl implements JpathCalendarService {

    private final JpathClock clock;
    private final JpathUserPathRepository paths;
    private final JpathUserLevelRepository levels;
    private final JpathDailyPlanRepository plans;

    public JpathCalendarServiceImpl(JpathClock clock, JpathUserPathRepository paths, JpathUserLevelRepository levels,
            JpathDailyPlanRepository plans) {
        this.clock = clock;
        this.paths = paths;
        this.levels = levels;
        this.plans = plans;
    }

    @Override
    public JpathDayKind dayKind(JpathUserPath path, JpathStage stage, LocalDate day) {
        return dayKind(path, stage, day, false);
    }

    @Override
    public JpathDayKind dayKind(JpathUserPath path, JpathStage stage, LocalDate day, boolean noNewItemsLeft) {
        Set<DayOfWeek> skill = JpathDays.parse(path.getSkillDays());
        Set<DayOfWeek> rest = JpathDays.parse(path.getRestDays());
        boolean skillPractice = stage == JpathStage.SKILL_PRACTICE;
        DayOfWeek third = skillPractice ? resolveThirdDay(path.getThirdSkillDay(), skill, rest) : null;
        return JpathCalendarService.kindOf(day, skill, rest, third, skillPractice, noNewItemsLeft);
    }

    @Override
    public Set<DayOfWeek> skillDays(JpathUserPath path, JpathStage stage) {
        EnumSet<DayOfWeek> skill = JpathDays.parse(path.getSkillDays());
        if (stage == JpathStage.SKILL_PRACTICE) {
            DayOfWeek third = resolveThirdDay(path.getThirdSkillDay(), skill, JpathDays.parse(path.getRestDays()));
            if (third != null) {
                skill.add(third);
            }
        }
        return skill;
    }

    @Override
    public DayOfWeek thirdSkillDay(Set<DayOfWeek> chosen, Set<DayOfWeek> rest) {
        return JpathCalendarService.autoThirdDay(chosen, rest);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JpathCalendarDayView> week(Long userId, LocalDate anyDay) {
        JpathUserPath path = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                .orElse(null);
        if (path == null) {
            return List.of();
        }
        JpathStage stage = levels.findById(new JpathUserLevelId(path.getId(), path.getOpenLevel()))
                .map(JpathUserLevel::getStage).orElse(JpathStage.STUDY);
        LocalDate monday = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        Map<LocalDate, JpathDailyPlan> stored = plans.findByUserIdAndStudyDayBetween(userId, monday, sunday).stream()
                .collect(Collectors.toMap(JpathDailyPlan::getStudyDay, Function.identity(), (a, b) -> a));
        LocalDate today = clock.today(userId);
        List<JpathCalendarDayView> week = new ArrayList<>(7);
        for (LocalDate day = monday; !day.isAfter(sunday); day = day.plusDays(1)) {
            JpathDailyPlan plan = stored.get(day);
            JpathDayKind kind = plan != null && plan.getDayKind() != null
                    ? plan.getDayKind() : dayKind(path, stage, day);
            boolean done = plan != null && plan.getAllDoneAt() != null;
            week.add(new JpathCalendarDayView(day, kind, done, day.equals(today)));
        }
        return week;
    }

    /** The stored third skill day, or the automatic one when it is missing, invalid, a rest day or a chosen day. */
    private static DayOfWeek resolveThirdDay(String stored, Set<DayOfWeek> skill, Set<DayOfWeek> rest) {
        if (stored != null && !stored.isBlank()) {
            try {
                Set<DayOfWeek> parsed = JpathDays.parse(stored);
                if (parsed.size() == 1) {
                    DayOfWeek day = parsed.iterator().next();
                    if (!skill.contains(day) && !rest.contains(day)) {
                        return day;
                    }
                }
            } catch (ApiException ignored) {
                // invalid stored code → fall back to the automatic day
            }
        }
        return JpathCalendarService.autoThirdDay(skill, rest);
    }
}
