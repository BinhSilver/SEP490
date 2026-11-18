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
     * Owner 18/11: kana rows of a kana-stage day follow the chosen minutes — 15→2, 30→4, 60→6, then one more row
     * per 15 minutes (90→8, 120→10), at most 12.
     */
    public static int kanaRowsPerDay(int minutes) {
        int rows = minutes <= 30 ? (int) Math.round(minutes / 7.5) : 4 + (int) Math.round((minutes - 30) / 15.0);
        return Math.max(KANA_ROWS_PER_DAY, Math.min(12, rows));
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
        boolean blocked = in.enforceTarget() && isLate(walk(in, recommend(openLevel, m), m), exam);

        List<JpathPaceSuggestion> suggestions = new ArrayList<>();
        int skipped = 0;
        if (late) {
            for (int option : MINUTE_OPTIONS) {
                if (option <= m) {
                    continue;
                }
                Walk faster = walk(in, recommend(openLevel, option), option);
                if (!isLate(faster, exam)) {
                    suggestions.add(new JpathPaceSuggestion("MORE_MINUTES", option, null, null, faster.targetFinish()));
                    break;
                }
            }
            // AI 1 #12: no option alone is in time, but more minutes still brings the finish closer; offer the most
            int most = MINUTE_OPTIONS.get(MINUTE_OPTIONS.size() - 1);
            if (suggestions.isEmpty() && m < most) {
                Walk fastest = walk(in, recommend(openLevel, most), most);
                suggestions.add(new JpathPaceSuggestion("MORE_MINUTES", most, null, null, fastest.targetFinish()));
            }
            if (exam != null) {
                LocalDate from = latest(walk.targetFinish(), exam).plusDays(1);
                suggestions.add(new JpathPaceSuggestion("LATER_EXAM", null,
                        JpathExamCalendar.nextSessionOnOrAfter(from), null, walk.targetFinish()));
                for (int i = walk.ready().size() - 2; i >= 0; i--) {
                    LocalDate ready = walk.ready().get(i);
                    if (!ready.isAfter(exam.minusDays(1))) {
                        suggestions.add(new JpathPaceSuggestion("LOWER_TARGET", null, null,
                                in.levels().get(i).level(), ready));
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
            message = "Với " + m + " phút/ngày bạn cần khoảng "
                    + durationVi(ChronoUnit.DAYS.between(in.today(), walk.targetFinish())) + " để tới " + targetLevel;
        } else if (exam != null) {
            message = "Với " + m + " phút/ngày bạn kịp xong " + targetLevel + " trước ngày thi (dự kiến "
                    + DATE_VI.format(walk.targetFinish()) + ")";
        } else {
            message = "Dự kiến xong " + targetLevel + ": " + DATE_VI.format(walk.targetFinish());
        }

        int minutesPerDay = dayMinutes(in.sliders(), in.kanaRowsLeft() > 0, in.mode(), m);
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
            JpathSliderValues s = i == 0 ? openSliders : recommend(level.level(), minutes);
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
