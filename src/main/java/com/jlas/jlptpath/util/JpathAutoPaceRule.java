package com.jlas.jlptpath.util;

import com.jlas.jlptpath.dto.JpathPaceInput;
import com.jlas.jlptpath.dto.JpathPaceResult;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.enums.JpathTab;
import java.time.LocalDate;

/**
 * [JLAS-W0108] F237 (owner 23/11): automatic pace after missed study days. When missed days push the finish past the
 * milestone — the exam date when the path has one, otherwise the open level's finish day first projected — the day's
 * words go up by at most {@link #MAX_RAISE} over the learner's chosen words per day, the smallest raise that is back on
 * the milestone; with +30% still late the full +30% is used and the later finish is told. Pure functions.
 */
public final class JpathAutoPaceRule {

    /** At most +30% over the chosen words per day. */
    public static final double MAX_RAISE = 0.30;

    /**
     * A raise: {@code words} new words per day instead of {@code chosenWords}; {@code reachable} = back on the
     * milestone with it; {@code finish} = the milestone kind's finish with it (target ready for an exam, open level
     * finish otherwise).
     */
    public record Raise(int chosenWords, int words, boolean reachable, LocalDate finish) {
    }

    private JpathAutoPaceRule() {
    }

    /** Largest words per day the raise may reach: ⌊chosen × 1.3⌋, within the level's limit. */
    public static int cap(String level, int chosen) {
        return Math.min((int) Math.floor(chosen * (1 + MAX_RAISE) + 1e-9), JpathSliderBounds.max(level, JpathTab.VOCAB));
    }

    /**
     * Raise for the chosen pace in {@code in} (its sliders' words = the chosen value), or null when none is due: no
     * missed day, on the milestone already, or no room above the chosen words. {@code milestone} null with no exam in
     * {@code in} = nothing to keep (null).
     */
    public static Raise decide(JpathPaceInput in, LocalDate milestone, int missedDays) {
        if (missedDays <= 0 || in.kanaRowsLeft() > 0) {
            return null;
        }
        boolean exam = in.examDate() != null;
        if (!exam && milestone == null) {
            return null;
        }
        int chosen = in.sliders().vocab();
        if (!late(JpathPacingEngine.estimate(in), exam, milestone)) {
            return null;
        }
        String level = in.levels().get(0).level();
        int cap = cap(level, chosen);
        if (cap <= chosen) {
            return null;
        }
        JpathPaceResult last = null;
        for (int words = chosen + 1; words <= cap; words++) {
            last = JpathPacingEngine.estimate(withWords(in, words));
            if (!late(last, exam, milestone)) {
                return new Raise(chosen, words, true, finish(last, exam));
            }
        }
        return new Raise(chosen, cap, false, finish(last, exam));
    }

    /** The open level's finish day with the chosen pace: the milestone a path without exam keeps. */
    public static LocalDate plannedFinish(JpathPaceInput in) {
        return JpathPacingEngine.estimate(in).levelFinish();
    }

    private static boolean late(JpathPaceResult r, boolean exam, LocalDate milestone) {
        return exam ? r.late() : r.levelFinish().isAfter(milestone);
    }

    private static LocalDate finish(JpathPaceResult r, boolean exam) {
        return exam ? r.targetFinish() : r.levelFinish();
    }

    private static JpathPaceInput withWords(JpathPaceInput in, int words) {
        JpathSliderValues s = in.sliders();
        return new JpathPaceInput(in.today(), in.levels(), new JpathSliderValues(words, s.kanji(), s.grammar()),
                in.skillDays(), in.restDays(), in.thirdSkillDay(), in.examDate(), in.kanaRowsLeft(), in.mode(),
                in.dailyMinutes(), false);
    }
}
