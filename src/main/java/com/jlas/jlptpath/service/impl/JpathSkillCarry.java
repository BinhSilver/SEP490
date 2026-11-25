package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathSkillSession;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathSkillSessionKind;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathSkillSessionRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [JLAS-W0108] F238 (owner 23/11): skill sessions (Đọc · Nghe) still owed from earlier weeks are no longer dropped on
 * Monday. The same week keeps its make-up day (#14); what is left after Sunday is carried and paid on the next normal
 * study days, at most one session a day after the main lesson, until nothing is owed.
 *
 * <p>Window: the {@value #CARRY_WEEKS} weeks before this week's Monday (from the day the path was created). Owed =
 * skill days of the calendar in the window × sessions per skill day − DAY sessions done in the window − DAY sessions
 * done this week on days that are not skill days. Older debt falls out of the window, so a learner who skips skills
 * for a month is not buried under it.
 */
final class JpathSkillCarry {

    /** Weeks looked back for owed skill sessions. */
    static final int CARRY_WEEKS = 2;
    /** Carried sessions a normal study day may take. */
    static final int PER_DAY = 1;

    /**
     * @param owed      carried sessions still owed before today's payment
     * @param skill     the skill to do next (the one done less in the window; READING on a tie)
     * @param doneToday a carried session was already done today (today's share is paid)
     */
    record Carry(int owed, JpathSkill skill, boolean doneToday) {
        static final Carry NONE = new Carry(0, JpathSkill.READING, false);

        /** Whether today may start one carried session. */
        boolean offerToday() {
            return owed > 0 && !doneToday;
        }
    }

    private JpathSkillCarry() {
    }

    static Carry compute(JpathCalendarService calendar, JpathDailyPlanRepository plans,
            JpathSkillSessionRepository sessions, JpathUserPath path, JpathStage stage, LocalDate day) {
        if (path == null || day == null) {
            return Carry.NONE;
        }
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate windowStart = monday.minusWeeks(CARRY_WEEKS);
        if (path.getCreatedAt() != null && path.getCreatedAt().toLocalDate().isAfter(windowStart)) {
            windowStart = path.getCreatedAt().toLocalDate();
        }
        LocalDate windowEnd = monday.minusDays(1);
        int scheduled = 0;
        for (LocalDate d = windowStart; !d.isAfter(windowEnd); d = d.plusDays(1)) {
            if (calendar.dayKind(path, stage, d) == JpathDayKind.SKILL) {
                scheduled++;
            }
        }
        if (scheduled == 0) {
            return Carry.NONE;
        }
        Long userId = path.getUserId();
        List<JpathSkillSession> prior = sessions.findByUserIdAndKindAndStudyDayBetweenAndCompletedAtIsNotNull(userId,
                JpathSkillSessionKind.DAY, windowStart, windowEnd);
        int perSkill = scheduled;
        long readingDone = prior.stream().filter(s -> s.getSkill() == JpathSkill.READING).count();
        long listeningDone = prior.stream().filter(s -> s.getSkill() == JpathSkill.LISTENING).count();
        long readingOwed = Math.max(0, perSkill - readingDone);
        long listeningOwed = Math.max(0, perSkill - listeningDone);

        Set<Long> skillPlanIds = plans.findByUserIdAndStudyDayBetween(userId, monday, day).stream()
                .filter(p -> p.getDayKind() == JpathDayKind.SKILL)
                .map(JpathDailyPlan::getId)
                .collect(Collectors.toSet());
        List<JpathSkillSession> paid = sessions.findByUserIdAndKindAndStudyDayBetweenAndCompletedAtIsNotNull(userId,
                        JpathSkillSessionKind.DAY, monday, day).stream()
                .filter(s -> s.getPlanId() == null || !skillPlanIds.contains(s.getPlanId()))
                .toList();
        for (JpathSkillSession s : paid) {
            if (s.getSkill() == JpathSkill.READING && readingOwed > 0) {
                readingOwed--;
            } else if (s.getSkill() == JpathSkill.LISTENING && listeningOwed > 0) {
                listeningOwed--;
            } else if (readingOwed > 0) {
                readingOwed--;
            } else if (listeningOwed > 0) {
                listeningOwed--;
            }
        }
        int owed = (int) (readingOwed + listeningOwed);
        boolean doneToday = paid.stream().filter(s -> day.equals(s.getStudyDay())).count() >= PER_DAY;
        JpathSkill next = listeningOwed > readingOwed ? JpathSkill.LISTENING : JpathSkill.READING;
        return new Carry(owed, next, doneToday);
    }
}
