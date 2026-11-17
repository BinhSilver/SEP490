package com.jlas.jlptpath.util;

import com.jlas.flashcard.util.SrsUtil;
import com.jlas.flashcard.util.SrsUtil.Schedule;
import com.jlas.flashcard.util.SrsUtil.State;
import com.jlas.jlptpath.enums.JpathRating;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] Review spacing for the JLPT path: the four review buttons on top of SM-2 ({@link SrsUtil}).
 *
 * <ul>
 *   <li>GOOD (q4): {@link SrsUtil#review}, but at least HARD interval + 1.</li>
 *   <li>HARD (q3): EF from SrsUtil, interval = max(1, round(previous × 1.2)).</li>
 *   <li>EASY (q5): interval = max(GOOD interval + 1, round(SrsUtil q5 interval × 1.3)).</li>
 *   <li>FORGOT (q1): SrsUtil (repetition 0, due tomorrow; shown as "&lt; 1 day").</li>
 *   <li>A correct early review keeps the schedule (SrsUtil {@code scheduled=false}).</li>
 * </ul>
 * {@code localNow} is the learner's local time, so the due day starts at the learner's midnight.
 */
public final class JpathSrsPolicy {
    public static final int MAX_INTERVAL_DAYS = 36500;
    static final double HARD_FACTOR = 1.2;
    static final double EASY_FACTOR = 1.3;
    static final long GRAMMAR_NO_TIMER_MS = 12_000;

    private static final List<JpathRating> FIRST_LEARN = List.of(JpathRating.REMEMBERED, JpathRating.FORGOT);
    private static final List<JpathRating> REVIEW =
            List.of(JpathRating.FORGOT, JpathRating.HARD, JpathRating.GOOD, JpathRating.EASY);

    private JpathSrsPolicy() { }

    public static int quality(JpathRating rating) {
        return switch (rating) {
            case REMEMBERED, GOOD -> 4;
            case FORGOT -> 1;
            case HARD -> 3;
            case EASY -> 5;
        };
    }

    public static Schedule apply(State s, JpathRating r, LocalDateTime localNow) {
        int q = quality(r);
        Schedule base = SrsUtil.review(s, q, localNow);
        if (!base.scheduled() || r == JpathRating.REMEMBERED || (q != 3 && q != 4 && q != 5)) {
            return base;
        }
        int interval;
        if (q == 3) {
            interval = hardInterval(s);
        } else if (q == 4) {
            // L18d: GOOD always lands after HARD (both were "1 ngày" on a young card)
            interval = Math.max(base.intervalDays(), hardInterval(s) + 1);
        } else {
            int good = Math.max(SrsUtil.review(s, 4, localNow).intervalDays(), hardInterval(s) + 1);
            interval = (int) Math.max(good + 1L, Math.round(base.intervalDays() * EASY_FACTOR));
        }
        interval = Math.min(MAX_INTERVAL_DAYS, interval);
        return new Schedule(base.status(), base.repetitions(), interval, base.easiness(),
                localNow.toLocalDate().plusDays(interval).atStartOfDay(), true);
    }

    private static int hardInterval(State s) {
        return (int) Math.max(1, Math.round(s.intervalDays() * HARD_FACTOR));
    }

    public static Map<JpathRating, Integer> previewDays(State s, boolean firstLearn, LocalDateTime localNow) {
        Map<JpathRating, Integer> result = new LinkedHashMap<>();
        for (JpathRating r : firstLearn ? FIRST_LEARN : REVIEW) {
            if (r == JpathRating.FORGOT) {
                result.put(r, 0);
                continue;
            }
            LocalDateTime next = apply(s, r, localNow).nextReviewAt();
            result.put(r, next == null ? 0
                    : (int) Math.max(0, ChronoUnit.DAYS.between(localNow.toLocalDate(), next.toLocalDate())));
        }
        return result;
    }

    public static JpathRating grammarAutoRating(boolean correct, long elapsedMs, Integer timerSec) {
        if (!correct) {
            return JpathRating.FORGOT;
        }
        long limit = timerSec == null ? GRAMMAR_NO_TIMER_MS : timerSec * 500L;
        return elapsedMs <= limit ? JpathRating.GOOD : JpathRating.HARD;
    }
}
