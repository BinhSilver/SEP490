package com.jlas.jlptpath.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathPaceInput;
import com.jlas.jlptpath.dto.JpathPaceLevel;
import com.jlas.jlptpath.dto.JpathPaceResult;
import com.jlas.jlptpath.dto.JpathPaceSuggestion;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathCalendarService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * [JLAS-W0108] Minute model of the JLPT path: minutes per unit (policy #1), slider recommendation from daily
 * minutes (#2), estimated minutes of a study day (#4) and the day-by-day pace estimate (#5, #6, #30). Pure functions.
 */
public final class JpathPacingEngine {

    public static final List<Integer> MINUTE_OPTIONS = List.of(15, 20, 30, 45, 60, 90, 120, 180);
    /** Owner 22/11 (F230): words per day the learner picks from; Kanji and grammar follow them. */
    public static final List<Integer> WORD_OPTIONS = List.of(5, 10, 15, 20, 25, 30, 40, 50);
    public static final double VOCAB_MIN = 0.6;
    public static final double KANJI_MIN = 1.25;
    public static final double GRAMMAR_MIN = 4.0;
    public static final double KANA_ROW_MIN = 6.0;
    public static final int KANA_ROWS_PER_DAY = 2;

    private static final double VOCAB_SHARE = 0.40;
    private static final double KANJI_SHARE = 0.33;
    private static final double GRAMMAR_SHARE = 0.27;

    /** Days walked per level before the estimate gives up and reports the target as late. */
    static final int MAX_DAYS_PER_LEVEL = 3650;
    private static final int SKILL_PRACTICE_DAYS = 14;
    private static final int PRIORITY_WINDOW_DAYS = 60;
    private static final JpathTab[] SLIDER_TABS = {JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR};
    private static final DateTimeFormatter DATE_VI = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private JpathPacingEngine() {
    }

    /** Policy #2: V=round(0.40m/0.6), K=round(0.33m/1.25), G=round(0.27m/4.0), each clamped to the level's limits. */
    public static JpathSliderValues recommend(String level, int minutes) {
        int v = (int) Math.round(VOCAB_SHARE * minutes / VOCAB_MIN);
        int k = (int) Math.round(KANJI_SHARE * minutes / KANJI_MIN);
        int g = (int) Math.round(GRAMMAR_SHARE * minutes / GRAMMAR_MIN);
        return new JpathSliderValues(
                JpathSliderBounds.clamp(level, JpathTab.VOCAB, v),
                JpathSliderBounds.clamp(level, JpathTab.KANJI, k),
                JpathSliderBounds.clamp(level, JpathTab.GRAMMAR, g));
    }

    /**
     * Owner 22/11 (F230): the learner picks words per day; Kanji and grammar per day follow so the three tabs of the
     * level finish on the same study day — D = ⌈vocab left / V⌉ study days, K = ⌈Kanji left / D⌉,
     * G = ⌈grammar left / D⌉ — each clamped to the level's limits. A tab with nothing left keeps its minimum.
     * With no vocab left, D comes from the recommended Kanji pace instead.
     */
    public static JpathSliderValues balance(String level, int vocabPerDay, int vocabLeft, int kanjiLeft,
            int grammarLeft) {
        int v = JpathSliderBounds.clamp(level, JpathTab.VOCAB, vocabPerDay);
        int days;
        if (vocabLeft > 0) {
            days = ceilDiv(vocabLeft, v);
        } else {
            int k = JpathSliderBounds.clamp(level, JpathTab.KANJI, (int) Math.round(v * KANJI_SHARE * VOCAB_MIN
                    / (VOCAB_SHARE * KANJI_MIN)));
            days = Math.max(1, Math.max(ceilDiv(Math.max(0, kanjiLeft), k), ceilDiv(Math.max(0, grammarLeft),
                    JpathSliderBounds.max(level, JpathTab.GRAMMAR))));
        }
        days = Math.max(1, days);
        return new JpathSliderValues(v,
                JpathSliderBounds.clamp(level, JpathTab.KANJI, ceilDiv(Math.max(0, kanjiLeft), days)),
                JpathSliderBounds.clamp(level, JpathTab.GRAMMAR, ceilDiv(Math.max(0, grammarLeft), days)));
    }

    /** {@link #balance} for the items of one pace level not counted yet. */
    public static JpathSliderValues balance(JpathPaceLevel l, int vocabPerDay) {
        return balance(l.level(), vocabPerDay, Math.max(0, l.vocabTotal() - l.vocabDone()),
                Math.max(0, l.kanjiTotal() - l.kanjiDone()), Math.max(0, l.grammarTotal() - l.grammarDone()));
    }

    /** The smallest minute option that holds a study day with these counts (the largest when none does). */
    public static int minutesOptionFor(JpathSliderValues sliders) {
        int need = dayMinutes(sliders, false, JpathStudyMode.COMBINED, 0);
        for (int option : MINUTE_OPTIONS) {
            if (option >= need) {
                return option;
            }
        }
        return MINUTE_OPTIONS.get(MINUTE_OPTIONS.size() - 1);
    }

    /** F230: the smallest minute option whose recommended words reach {@code words} (the largest when none does). */
    public static int minutesForWords(String level, int words) {
        for (int option : MINUTE_OPTIONS) {
            if (recommend(level, option).vocab() >= words) {
                return option;
            }
        }
        return MINUTE_OPTIONS.get(MINUTE_OPTIONS.size() - 1);
    }

    private static JpathSliderValues withVocab(JpathSliderValues s, int vocab) {
        return new JpathSliderValues(vocab, s.kanji(), s.grammar());
    }

    /** MORE_WORDS suggestion: {@code words} per day and the day the target is ready with them. */
    private static JpathPaceSuggestion moreWords(int words, LocalDate finish) {
        return new JpathPaceSuggestion("MORE_WORDS", null, null, null, finish, words);
    }

    private static int ceilDiv(int a, int b) {
        return b <= 0 ? a : (a + b - 1) / b;
    }

    /**
     * Owner 18/11: kana rows of a kana-stage day follow the chosen minutes — 15→2, 30→4, 60→6, then one more row
     * per 15 minutes (90→8, 120→10), at most 12.
     */
    public static int kanaRowsPerDay(int minutes) {
        int rows = minutes <= 30 ? (int) Math.round(minutes / 7.5) : 4 + (int) Math.round((minutes - 30) / 15.0);
        return Math.max(KANA_ROWS_PER_DAY, Math.min(12, rows));
    }

    /** Kana rows of both tables (Hiragana + Katakana, basic, dakuten, yoon): the size the target-date rule plans for. */
    public static final int KANA_TOTAL_ROWS = 54;

    /**
     * Owner 18/11 (kana stage): rows per day follow the free time and the target date too. With an exam date the kana
     * stage should fit in about a tenth of the days left (at least 3 days), so a close date raises the rows; never fewer
     * than the minutes give, at most 12.
     */
    public static int kanaRowsPerDay(int minutes, java.time.LocalDate today, java.time.LocalDate examDate) {
        int byMinutes = kanaRowsPerDay(minutes);
        if (today == null || examDate == null || !examDate.isAfter(today)) return byMinutes;
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, examDate);
        long kanaDays = Math.max(3, days / 10);
        int byDate = (int) Math.ceil(KANA_TOTAL_ROWS / (double) kanaDays);
        return Math.max(byMinutes, Math.min(12, byDate));
    }

    /** Kana-phase vocabulary per day (policy #26): max(3, round(m/6)). */
    public static int kanaPhaseVocab(int minutes) {
        return Math.max(3, (int) Math.round(minutes / 6.0));
    }

    /**
     * Policy #4: normal day = round(0.6V + 1.25K + 4G); kana day COMBINED = round(12 + 0.6×kanaPhaseVocab);
     * kana day SPLIT = round(12 + 0.6×kanaPhaseVocab + 1.25K + 4G).
     */
    public static int dayMinutes(JpathSliderValues sliders, boolean kanaPhase, JpathStudyMode mode, int minutes) {
        double kanjiGrammar = KANJI_MIN * sliders.kanji() + GRAMMAR_MIN * sliders.grammar();
        if (!kanaPhase) {
            return (int) Math.round(VOCAB_MIN * sliders.vocab() + kanjiGrammar);
        }
        // owner 18/11: kana stage = kana rows only
        return (int) Math.round(kanaRowsPerDay(minutes) * KANA_ROW_MIN);
    }

    public static void requireMinuteOption(int minutes) {
        if (!MINUTE_OPTIONS.contains(minutes)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Số phút mỗi ngày không hợp lệ.");
        }
    }

    /**
     * Policy #5/#6/#30: walks day by day from {@code today} through the levels (open → target). STUDY days add the
     * sliders (kana phase: 2 rows + kana-phase vocabulary; SPLIT also adds kanji and grammar); skill practice starts at
     * the end of the first day all 3 tabs reach 80%; a level is ready at max(level finish, practice start + 14); the
     * next level starts the day after. Late when the target is ready after the day before the exam.
     */
    public static JpathPaceResult estimate(JpathPaceInput in) {
        Objects.requireNonNull(in.mode(), "mode");
        int m = in.dailyMinutes();
        String openLevel = in.levels().get(0).level();
        String targetLevel = in.levels().get(in.levels().size() - 1).level();
        LocalDate exam = in.examDate();

        Walk walk = walk(in, in.sliders(), m);
        boolean late = isLate(walk, exam);
        // F230: the learner picks the words per day, so the chosen pace is the one that must make it
        boolean blocked = in.enforceTarget() && late;

        List<JpathPaceSuggestion> suggestions = new ArrayList<>();
        int skipped = 0;
        int words = JpathSliderBounds.clamp(openLevel, JpathTab.VOCAB, in.sliders().vocab());
        if (late) {
            // F230: the way out is more words per day (Kanji and grammar follow them)
            for (int option : WORD_OPTIONS) {
                if (option <= words || option > JpathSliderBounds.max(openLevel, JpathTab.VOCAB)) {
                    continue;
                }
                Walk faster = walk(in, withVocab(in.sliders(), option), m);
                if (!isLate(faster, exam)) {
                    suggestions.add(moreWords(option, faster.targetFinish()));
                    break;
                }
            }
            // AI 1 #12: no option alone is in time, but more words still brings the finish closer; offer the most
            int most = JpathSliderBounds.max(openLevel, JpathTab.VOCAB);
            if (suggestions.isEmpty() && words < most) {
                Walk fastest = walk(in, withVocab(in.sliders(), most), m);
                suggestions.add(moreWords(most, fastest.targetFinish()));
            }
            if (exam != null) {
                LocalDate from = latest(walk.targetFinish(), exam).plusDays(1);
                suggestions.add(new JpathPaceSuggestion("LATER_EXAM", null,
                        JpathExamCalendar.nextSessionOnOrAfter(from), null, walk.targetFinish(), null));
                for (int i = walk.ready().size() - 2; i >= 0; i--) {
                    LocalDate ready = walk.ready().get(i);
                    if (!ready.isAfter(exam.minusDays(1))) {
                        suggestions.add(new JpathPaceSuggestion("LOWER_TARGET", null, null,
                                in.levels().get(i).level(), ready, null));
                        break;
                    }
                }
                if (ChronoUnit.DAYS.between(in.today(), exam) < PRIORITY_WINDOW_DAYS) {
                    skipped = skippedLowPriority(in, exam);
                }
            }
        }

        String message;
        if (late) {
            message = "Với " + words + " từ mỗi ngày bạn cần khoảng "
                    + durationVi(ChronoUnit.DAYS.between(in.today(), walk.targetFinish())) + " để tới " + targetLevel;
        } else if (exam != null) {
            message = "Với " + words + " từ mỗi ngày bạn kịp xong " + targetLevel + " trước ngày thi (dự kiến "
                    + DATE_VI.format(walk.targetFinish()) + ")";
        } else {
            message = "Dự kiến xong " + targetLevel + ": " + DATE_VI.format(walk.targetFinish());
        }

        // F230: the day is costed with the balanced counts the walk actually uses
        int minutesPerDay = dayMinutes(balance(in.levels().get(0), in.sliders().vocab()), in.kanaRowsLeft() > 0,
                in.mode(), m);
        return new JpathPaceResult(walk.openTabFinish(), walk.openLevelFinish(), walk.targetFinish(), blocked, late,
                minutesPerDay > m, minutesPerDay, List.copyOf(suggestions), skipped, message);
    }

    /** Duration in Vietnamese: under 60 days → weeks (rounded up), otherwise months (rounded). */
    public static String durationVi(long days) {
        if (days < PRIORITY_WINDOW_DAYS) {
            return Math.max(0, (days + 6) / 7) + " tuần";
        }
        return Math.round(days / 30.0) + " tháng";
    }

    private record Walk(Map<JpathTab, LocalDate> openTabFinish, LocalDate openLevelFinish, List<LocalDate> ready,
            LocalDate targetFinish, boolean capped) {
    }

    private static boolean isLate(Walk walk, LocalDate exam) {
        return walk.capped() || (exam != null && walk.targetFinish().isAfter(exam.minusDays(1)));
    }

    private static Walk walk(JpathPaceInput in, JpathSliderValues openSliders, int minutes) {
        Set<DayOfWeek> skill = orEmpty(in.skillDays());
        Set<DayOfWeek> rest = orEmpty(in.restDays());
        DayOfWeek third = thirdDay(in.thirdSkillDay(), skill, rest);
        LocalDate day = in.today();
        int kana = Math.max(0, in.kanaRowsLeft());
        Map<JpathTab, LocalDate> openTabFinish = new EnumMap<>(JpathTab.class);
        LocalDate openLevelFinish = null;
        List<LocalDate> readyDays = new ArrayList<>();

        for (int i = 0; i < in.levels().size(); i++) {
            JpathPaceLevel level = in.levels().get(i);
            // F230: every level's three tabs finish together
            JpathSliderValues s = balance(level, openSliders.vocab()); // the same words per day on every level
            int[] total = {level.vocabTotal(), level.kanjiTotal(), level.grammarTotal()};
            int[] done = {Math.min(level.vocabDone(), total[0]), Math.min(level.kanjiDone(), total[1]),
                    Math.min(level.grammarDone(), total[2])};
            LocalDate[] finish = new LocalDate[3];
            for (int t = 0; t < 3; t++) {
                if (done[t] >= total[t]) {
                    finish[t] = day;
                }
            }
            LocalDate practice = level.skillPracticeSince();
            boolean capped = false;
            int walked = 0;
            while (!(allDone(done, total) && practice != null)) {
                if (walked++ >= MAX_DAYS_PER_LEVEL) {
                    capped = true;
                    break;
                }
                JpathDayKind kind = JpathCalendarService.kindOf(day, skill, rest, third, practice != null,
                        allDone(done, total));
                if (kind == JpathDayKind.STUDY) {
                    int[] add;
                    if (kana > 0) {
                        kana = Math.max(0, kana - kanaRowsPerDay(minutes));
                        add = new int[] {0, 0, 0}; // owner 18/11: kana stage teaches no new words
                    } else {
                        add = new int[] {s.vocab(), s.kanji(), s.grammar()};
                    }
                    for (int t = 0; t < 3; t++) {
                        if (done[t] < total[t]) {
                            done[t] = Math.min(total[t], done[t] + add[t]);
                            if (done[t] == total[t]) {
                                finish[t] = day;
                            }
                        }
                    }
                }
                if (practice == null && reachedPractice(done, total)) {
                    practice = day;
                }
                day = day.plusDays(1);
            }

            LocalDate levelFinish = capped ? day : latest(finish);
            LocalDate ready = capped ? day : latest(levelFinish, practice.plusDays(SKILL_PRACTICE_DAYS));
            if (i == 0) {
                for (int t = 0; t < 3; t++) {
                    if (finish[t] != null) {
                        openTabFinish.put(SLIDER_TABS[t], finish[t]);
                    }
                }
                openLevelFinish = levelFinish;
            }
            readyDays.add(ready);
            if (capped) {
                return new Walk(Collections.unmodifiableMap(openTabFinish), openLevelFinish, List.copyOf(readyDays),
                        ready, true);
            }
            day = ready.plusDays(1);
        }
        return new Walk(Collections.unmodifiableMap(openTabFinish), openLevelFinish, List.copyOf(readyDays),
                readyDays.get(readyDays.size() - 1), false);
    }

    /** #30: items of the open level that do not fit in the STUDY days from today to the day before the exam. */
    private static int skippedLowPriority(JpathPaceInput in, LocalDate exam) {
        Set<DayOfWeek> skill = orEmpty(in.skillDays());
        Set<DayOfWeek> rest = orEmpty(in.restDays());
        JpathPaceLevel open = in.levels().get(0);
        boolean practice = open.skillPracticeSince() != null;
        DayOfWeek third = thirdDay(in.thirdSkillDay(), skill, rest);
        int studyDays = 0;
        for (LocalDate day = in.today(); day.isBefore(exam); day = day.plusDays(1)) {
            if (JpathCalendarService.kindOf(day, skill, rest, third, practice, false) == JpathDayKind.STUDY) {
                studyDays++;
            }
        }
        JpathSliderValues s = in.sliders();
        return Math.max(0, open.vocabTotal() - open.vocabDone() - s.vocab() * studyDays)
                + Math.max(0, open.kanjiTotal() - open.kanjiDone() - s.kanji() * studyDays)
                + Math.max(0, open.grammarTotal() - open.grammarDone() - s.grammar() * studyDays);
    }

    /** Stored third day when it is free (neither chosen nor rest); otherwise the automatic choice. */
    private static DayOfWeek thirdDay(DayOfWeek stored, Set<DayOfWeek> skill, Set<DayOfWeek> rest) {
        if (stored != null && !skill.contains(stored) && !rest.contains(stored)) {
            return stored;
        }
        return JpathCalendarService.autoThirdDay(skill, rest);
    }

    private static Set<DayOfWeek> orEmpty(Set<DayOfWeek> days) {
        return days == null ? EnumSet.noneOf(DayOfWeek.class) : days;
    }

    private static boolean allDone(int[] done, int[] total) {
        return done[0] >= total[0] && done[1] >= total[1] && done[2] >= total[2];
    }

    /** Skill practice threshold of the estimate: done × 5 ≥ total × 4 on all 3 tabs (policy #15). */
    private static boolean reachedPractice(int[] done, int[] total) {
        for (int t = 0; t < 3; t++) {
            if (done[t] * 5L < total[t] * 4L) {
                return false;
            }
        }
        return true;
    }

    private static LocalDate latest(LocalDate... days) {
        LocalDate max = days[0];
        for (LocalDate d : days) {
            if (d.isAfter(max)) {
                max = d;
            }
        }
        return max;
    }
}
