package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathCalendarDayView;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathStage;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * [JLAS-W0108] Week calendar of the JLPT path: REST beats SKILL beats STUDY. In SKILL_PRACTICE a third skill day is
 * added (plan decision 14). The static rules are shared with the path estimate so both see the same calendar.
 */
public interface JpathCalendarService {

    /** Same as {@link #dayKind(JpathUserPath, JpathStage, LocalDate, boolean)} with {@code noNewItemsLeft=false}. */
    JpathDayKind dayKind(JpathUserPath path, JpathStage stage, LocalDate day);

    /** Kind of {@code day}; in SKILL_PRACTICE with no new items left every non-rest day is SKILL. */
    JpathDayKind dayKind(JpathUserPath path, JpathStage stage, LocalDate day, boolean noNewItemsLeft);

    /** The 2 chosen skill days, plus the third one in SKILL_PRACTICE. */
    Set<DayOfWeek> skillDays(JpathUserPath path, JpathStage stage);

    /** Third skill day picked for the learner's chosen skill and rest days. */
    DayOfWeek thirdSkillDay(Set<DayOfWeek> chosen, Set<DayOfWeek> rest);

    /** Same as {@link #week(Long, LocalDate, boolean)} with {@code noNewItemsLeft=false}. */
    List<JpathCalendarDayView> week(Long userId, LocalDate anyDay);

    /**
     * Monday→Sunday of the week holding {@code anyDay}; empty when the learner has no active path. A day with a stored
     * plan keeps its kind; {@code noNewItemsLeft} (the open level has no new item left) applies to today and later.
     */
    List<JpathCalendarDayView> week(Long userId, LocalDate anyDay, boolean noNewItemsLeft);

    /**
     * Plan decision 14: among the days that are neither chosen nor rest, the one whose smallest week-circular
     * distance to the chosen days is largest; ties → the earlier day (Monday→Sunday). Null when no day is free.
     */
    static DayOfWeek autoThirdDay(Set<DayOfWeek> chosen, Set<DayOfWeek> rest) {
        DayOfWeek best = null;
        int bestScore = -1;
        for (DayOfWeek candidate : DayOfWeek.values()) {
            if (chosen.contains(candidate) || (rest != null && rest.contains(candidate))) {
                continue;
            }
            int score = Integer.MAX_VALUE;
            for (DayOfWeek c : chosen) {
                int diff = Math.abs(candidate.getValue() - c.getValue());
                score = Math.min(score, Math.min(diff, 7 - diff));
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The one calendar rule: REST if the day is a rest day; SKILL if SKILL_PRACTICE has no new items left, if the day
     * is a chosen skill day, or (in SKILL_PRACTICE) the third skill day; otherwise STUDY.
     *
     * @param third the resolved third skill day; only used when {@code skillPractice}
     */
    static JpathDayKind kindOf(LocalDate day, Set<DayOfWeek> skill, Set<DayOfWeek> rest, DayOfWeek third,
            boolean skillPractice, boolean noNewLeft) {
        DayOfWeek dow = day.getDayOfWeek();
        if (rest != null && rest.contains(dow)) {
            return JpathDayKind.REST;
        }
        if (skillPractice && noNewLeft) {
            return JpathDayKind.SKILL;
        }
        if (skill.contains(dow) || (skillPractice && dow == third)) {
            return JpathDayKind.SKILL;
        }
        return JpathDayKind.STUDY;
    }
}
