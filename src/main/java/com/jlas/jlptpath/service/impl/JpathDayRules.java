package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyPlanRepository;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.service.JpathCalendarService;
import com.jlas.jlptpath.util.JpathPacingEngine;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [JLAS-W0108] Day rules of the daily plan (no transaction of their own): the kind of a day with the skill-day
 * make-up (#14) and the target/cap table of each day kind (#13, #26).
 */
final class JpathDayRules {

    /** Review cap of the kana tab on every day (policy #13). */
    static final int KANA_REVIEW_CAP = 20;
    /** Kana rows of a kana-phase study day (policy #26). */
    static final int KANA_ROWS_PER_DAY = JpathPacingEngine.KANA_ROWS_PER_DAY;

    /** Kind of a day: the calendar kind, a study day turned into a make-up skill day, and the kana phase. */
    record DayPlan(JpathDayKind kind, boolean skillMakeup, boolean kanaPhase, boolean noNewItemsLeft) {
    }

    private JpathDayRules() {
    }

    /**
     * Kind of {@code day} for the path: the calendar kind (SKILL_PRACTICE with no new item left → SKILL), and a STUDY
     * day with skill days owed this week becomes a make-up SKILL day (#14). Kana phase = kana tab and kana not done.
     */
    static DayPlan dayPlan(JpathContentReader content, JpathCalendarService calendar, JpathDailyPlanRepository plans,
            JpathUserPath path, JpathUserLevel level, LocalDate day) {
        JpathStage stage = level.getStage();
        boolean kanaPhase = path.isKanaTab() && path.getKanaCompletedAt() == null;
        boolean noNewLeft = noNewItemsLeft(content, path, level);
        JpathDayKind kind = calendar.dayKind(path, stage, day, noNewLeft);
        // walkthrough 24/11: kana comes first; a Reading/Listening day before the kana stage is done cannot be read,
        // so a calendar skill day in the kana phase is a study (kana) day and owes nothing later
        if (kanaPhase) {
            return new DayPlan(kind == JpathDayKind.SKILL ? JpathDayKind.STUDY : kind, false, true, noNewLeft);
        }
        if (kind == JpathDayKind.STUDY && owedSkillDays(calendar, plans, path, stage, day) > 0) {
            return new DayPlan(JpathDayKind.SKILL, true, kanaPhase, noNewLeft);
        }
        return new DayPlan(kind, false, kanaPhase, noNewLeft);
    }

    /** "No new items left" of the calendar: only looked up in SKILL_PRACTICE, where it changes the day kind. */
    static boolean noNewItemsLeft(JpathContentReader content, JpathUserPath path, JpathUserLevel level) {
        return level.getStage() == JpathStage.SKILL_PRACTICE
                && JpathPathRules.noNewItemsLeft(content, path.getUserId(), level.getLevel(), level.isPriorityMode());
    }

    /**
     * Policy #14: skill days of the calendar this week (Monday→Sunday) before {@code day}, counted from the day the
     * path was created, minus this week's SKILL plans before {@code day} that are done; never negative.
     */
    static int owedSkillDays(JpathCalendarService calendar, JpathDailyPlanRepository plans, JpathUserPath path,
            JpathStage stage, LocalDate day) {
        if (path.isKanaTab() && path.getKanaCompletedAt() == null) {
            return 0; // kana phase: no skill day is owed (dayPlan), nor warned about on the path page
        }
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = monday;
        if (path.getCreatedAt() != null && path.getCreatedAt().toLocalDate().isAfter(from)) {
            from = path.getCreatedAt().toLocalDate();
        }
        // skill days of the kana phase are not owed (see dayPlan)
        if (path.isKanaTab() && path.getKanaCompletedAt() != null && path.getKanaCompletedAt().toLocalDate().isAfter(from)) {
            from = path.getKanaCompletedAt().toLocalDate();
        }
        int scheduled = 0;
        for (LocalDate d = from; d.isBefore(day); d = d.plusDays(1)) {
            if (calendar.dayKind(path, stage, d) == JpathDayKind.SKILL) {
                scheduled++;
            }
        }
        if (scheduled == 0) {
            return 0;
        }
        long done = day.equals(monday) ? 0 : plans.findByUserIdAndStudyDayBetween(path.getUserId(), monday,
                        day.minusDays(1)).stream()
                .filter(p -> p.getDayKind() == JpathDayKind.SKILL && p.getAllDoneAt() != null)
                .count();
        return (int) Math.max(0, scheduled - done);
    }

    /**
     * Tab rows of a day kind (V/K/G = the open level's sliders, m = daily minutes), plan id and timestamps unset:
     * STUDY V/K/G caps 2×; kana phase VOCAB = kanaPhaseVocab(m), K/G 0 when COMBINED, KANA 2 rows cap 20; SKILL all 0
     * plus the SKILL tab (cap 0); REVIEW_ONLY 0 with caps 4×; REST 0 with caps 2×. KANA rows (cap 20) on non-study
     * days only for paths with the kana tab.
     */
    static List<JpathDailyTab> tabRows(JpathDayKind kind, boolean kanaPhase, boolean kanaTab, JpathStudyMode mode,
            JpathUserLevel level, int minutes) {
        return tabRows(kind, kanaPhase, kanaTab, mode, level, minutes, JpathPacingEngine.kanaRowsPerDay(minutes));
    }

    /** Kana rows of a kana-stage day for this path on {@code day}: free time and exam date (owner 18/11). */
    static int kanaRows(com.jlas.jlptpath.entity.JpathUserPath path, java.time.LocalDate day) {
        int minutes = path.getDailyMinutes() == null ? 30 : path.getDailyMinutes();
        return JpathPacingEngine.kanaRowsPerDay(minutes, day, path.getExamDate());
    }

    static List<JpathDailyTab> tabRows(JpathDayKind kind, boolean kanaPhase, boolean kanaTab, JpathStudyMode mode,
            JpathUserLevel level, int minutes, int kanaRows) {
        return tabRows(kind, kanaPhase, kanaTab, mode, new JpathSliderValues(level.getSliderVocab(),
                level.getSliderKanji(), level.getSliderGrammar()), minutes, kanaRows);
    }

    /** F237: the table rows with {@code sliders} instead of the level's (today's words raised after missed days). */
    static List<JpathDailyTab> tabRows(JpathDayKind kind, boolean kanaPhase, boolean kanaTab, JpathStudyMode mode,
            JpathSliderValues sliders, int minutes, int kanaRows) {
        int v = sliders.vocab();
        int k = sliders.kanji();
        int g = sliders.grammar();
        List<JpathDailyTab> out = new ArrayList<>(5);
        switch (kind) {
            case STUDY -> {
                if (kanaPhase) {
                    // owner 18/11: the kana stage comes first and teaches kana only; new words start after it
                    out.add(tab(JpathTab.VOCAB, 0, 2 * v));
                    out.add(tab(JpathTab.KANJI, 0, 2 * k));
                    out.add(tab(JpathTab.GRAMMAR, 0, 2 * g));
                    out.add(tab(JpathTab.KANA, kanaRows, KANA_REVIEW_CAP));
                } else {
                    out.add(tab(JpathTab.VOCAB, v, 2 * v));
                    out.add(tab(JpathTab.KANJI, k, 2 * k));
                    out.add(tab(JpathTab.GRAMMAR, g, 2 * g));
                }
            }
            case SKILL -> {
                addZero(out, 2, v, k, g, kanaTab);
                // Plan 3 Task 16: one per skill (Reading, Listening); the inserter lowers it when content is missing
                out.add(tab(JpathTab.SKILL, SKILL_SESSIONS_PER_DAY, 0));
            }
            case REVIEW_ONLY -> addZero(out, 4, v, k, g, kanaTab);
            case REST -> addZero(out, 2, v, k, g, kanaTab);
        }
        return out;
    }

    /** Plan 3 Task 16: a skill day asks one DAY session of each skill. */
    static final int SKILL_SESSIONS_PER_DAY = 2;

    /** A tab with target 0 (other than SKILL) is done from the start. */
    static void markZeroTargetDone(JpathDailyTab tab, LocalDateTime now) {
        if (tab.getTab() == JpathTab.SKILL) {
            return;
        }
        if (tab.getNewTarget() <= 0) {
            if (tab.getDoneAt() == null) {
                tab.setDoneAt(now);
            }
        } else {
            tab.setDoneAt(null);
        }
    }

    /**
     * Writes the table rows {@code fresh} onto the plan's {@code tabs}, skipping {@code kept} and an existing SKILL
     * tab; rows missing from the plan are added.
     */
    static void applyRows(JpathDailyPlan plan, List<JpathDailyTab> tabs, List<JpathDailyTab> fresh,
            Set<JpathTab> kept, LocalDateTime now, JpathDailyTabRepository dailyTabs) {
        Map<JpathTab, JpathDailyTab> byTab = new EnumMap<>(JpathTab.class);
        tabs.forEach(t -> byTab.put(t.getTab(), t));
        for (JpathDailyTab row : fresh) {
            if (kept.contains(row.getTab()) || row.getTab() == JpathTab.SKILL && byTab.containsKey(JpathTab.SKILL)) {
                continue;
            }
            JpathDailyTab t = byTab.get(row.getTab());
            if (t == null) {
                row.setPlanId(plan.getId());
                markZeroTargetDone(row, now);
                dailyTabs.save(row);
                continue;
            }
            t.setNewTarget(row.getNewTarget());
            t.setReviewCap(row.getReviewCap());
            markZeroTargetDone(t, now);
        }
    }

    private static void addZero(List<JpathDailyTab> out, int capFactor, int v, int k, int g, boolean kanaTab) {
        out.add(tab(JpathTab.VOCAB, 0, capFactor * v));
        out.add(tab(JpathTab.KANJI, 0, capFactor * k));
        out.add(tab(JpathTab.GRAMMAR, 0, capFactor * g));
        if (kanaTab) {
            out.add(tab(JpathTab.KANA, 0, KANA_REVIEW_CAP));
        }
    }

    private static JpathDailyTab tab(JpathTab tab, int target, int cap) {
        return JpathDailyTab.builder().tab(tab).newTarget(target).reviewCap(cap).build();
    }
}
