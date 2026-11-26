package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathStage;
import com.jlas.jlptpath.service.JpathCalendarService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.Set;

/**
 * [JLAS-W0108] Numbers of the JLPT path skill sessions: per-session floors and minute estimate (policy #34), section
 * timer and pass floor (#40), skill days per week (read from the path calendar rule) and the early skill-practice
 * threshold (#46). No state, no I/O.
 */
public final class JpathSkillPolicy {

    /** A reading set with at least this many characters counts as a long passage (#34, N2–N1). */
    public static final int LONG_PASSAGE_CHARS = 600;
    /** Dictation lines per listening session (#34). */
    public static final int DICTATION_FLOOR = 5;
    /** Section practice pass floor: 19/60 rounded up (#40). */
    public static final int SECTION_PASS_PCT = 32;
    /** Q7 (owner 24/11): a skill day counts as done only from this practice score on. */
    public static final int DAY_PASS_PCT = 50;
    /** Per-tab percent to enter skill practice (#15 of plan 2). */
    public static final int STAGE_THRESHOLD_PCT = 80;
    /** Per-tab percent to enter skill practice in priority mode (#46). */
    public static final int PRIORITY_STAGE_THRESHOLD_PCT = 60;

    private static final double READING_CHARS_PER_MINUTE = 250.0;
    private static final double READING_MINUTES_PER_QUESTION = 1.0;
    private static final double LISTENING_AUDIO_FACTOR = 1.5;
    private static final double LISTENING_MINUTES_PER_QUESTION = 0.5;
    private static final double DICTATION_MINUTES_PER_LINE = 0.7;

    /**
     * Reference week for {@link #skillDaysPerWeek}: two chosen skill days (the plan 2 default, accepted by
     * {@link JpathDays#validate}), no rest day.
     */
    private static final Set<DayOfWeek> REFERENCE_SKILL_DAYS = EnumSet.of(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY);

    static {
        JpathDays.validate(REFERENCE_SKILL_DAYS, Set.of());
    }

    private JpathSkillPolicy() {
    }

    /**
     * Minimum content of one session.
     *
     * @param passages  reading sets (0 for listening)
     * @param questions practice questions
     * @param needsLong at least one set with {@link #LONG_PASSAGE_CHARS} characters or more
     * @param dictation dictation lines (0 for reading)
     */
    public record Floor(int passages, int questions, boolean needsLong, int dictation) {
    }

    /** Policy #34 floor for {@code level} (N5…N1) and skill; unknown level → IllegalArgumentException. */
    public static Floor floor(String level, JpathSkill skill) {
        int rank = rank(level);
        boolean reading = skill == JpathSkill.READING;
        if (rank >= 4) { // N5, N4
            return reading ? new Floor(2, 6, false, 0) : new Floor(0, 10, false, DICTATION_FLOOR);
        }
        if (rank == 3) {
            return reading ? new Floor(3, 10, false, 0) : new Floor(0, 12, false, DICTATION_FLOOR);
        }
        return reading ? new Floor(3, 12, true, 0) : new Floor(0, 12, false, DICTATION_FLOOR);
    }

    /**
     * Estimated minutes (#34): reading {@code chars/250 + 1.0 × questions}; listening
     * {@code audioSeconds/60 × 1.5 + 0.5 × questions}; plus {@code 0.7 × dictation} lines.
     */
    public static double minutes(JpathSkill skill, int chars, int audioSeconds, int questions, int dictation) {
        double base = skill == JpathSkill.READING
                ? chars / READING_CHARS_PER_MINUTE + READING_MINUTES_PER_QUESTION * questions
                : audioSeconds / 60.0 * LISTENING_AUDIO_FACTOR + LISTENING_MINUTES_PER_QUESTION * questions;
        return base + DICTATION_MINUTES_PER_LINE * dictation;
    }

    /** Reading section timer (#40): questions × {N5 90, N4 100, N3 110, N2/N1 120} seconds. */
    public static int sectionSeconds(String level, int questions) {
        int perQuestion = switch (rank(level)) {
            case 5 -> 90;
            case 4 -> 100;
            case 3 -> 110;
            default -> 120;
        };
        return Math.max(0, questions) * perQuestion;
    }

    /** True when {@code pct ≥ 32}; null → false. */
    public static boolean sectionPassed(BigDecimal pct) {
        return pct != null && pct.compareTo(BigDecimal.valueOf(SECTION_PASS_PCT)) >= 0;
    }

    /**
     * Skill days in a week for a stage, counted with the path calendar rule
     * ({@link JpathCalendarService#kindOf}, third day from {@link JpathCalendarService#autoThirdDay}) on a reference
     * week with two chosen skill days and no rest day.
     */
    public static int skillDaysPerWeek(JpathStage stage) {
        boolean skillPractice = stage == JpathStage.SKILL_PRACTICE;
        DayOfWeek third = skillPractice ? JpathCalendarService.autoThirdDay(REFERENCE_SKILL_DAYS, Set.of()) : null;
        LocalDate monday = LocalDate.of(2026, 1, 5).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int count = 0;
        for (int i = 0; i < 7; i++) {
            if (JpathCalendarService.kindOf(monday.plusDays(i), REFERENCE_SKILL_DAYS, Set.of(), third, skillPractice,
                    false) == JpathDayKind.SKILL) {
                count++;
            }
        }
        return count;
    }

    /** Per-tab percent to enter skill practice: 60 in priority mode (#46), else 80. */
    public static int stageThresholdPct(boolean priorityMode) {
        return priorityMode ? PRIORITY_STAGE_THRESHOLD_PCT : STAGE_THRESHOLD_PCT;
    }

    /** N5 → 5 … N1 → 1; anything else → IllegalArgumentException. */
    private static int rank(String level) {
        if (level != null && level.length() == 2 && level.charAt(0) == 'N') {
            int n = level.charAt(1) - '0';
            if (n >= 1 && n <= 5) {
                return n;
            }
        }
        throw new IllegalArgumentException("Unknown JLPT level: " + level);
    }
}
