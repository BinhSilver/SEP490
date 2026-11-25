package com.jlas.flashcard.speedrecall;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/** Deterministic rules shared with the browser through contract fixtures. */
public final class SpeedRecallRules {
    public static final String VERSION = "reading-v1";
    private static final Pattern KANJI = Pattern.compile("\\p{IsHan}");
    private static final Pattern KANA = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}ー]+");
    private SpeedRecallRules() {}
    public static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC).strip();
    }
    public static boolean eligible(String front, String reading) {
        String f = normalize(front), r = normalize(reading);
        return f.codePointCount(0, f.length()) <= 500 && r.codePointCount(0, r.length()) <= 255
                && KANJI.matcher(f).find() && KANA.matcher(r).matches();
    }
    /** Seconds per word a learner may choose instead of the automatic time. */
    public static final List<Integer> TIME_LIMIT_CHOICES = List.of(6, 10, 15, 20, 30, 45, 60);
    public static int timeLimit(int streak) {
        return streak >= 20 ? 3000 : streak >= 10 ? 4000 : streak >= 5 ? 5000 : 6000;
    }
    /** The chosen seconds per word, or the automatic time that shortens with the streak when none was chosen. */
    public static int timeLimit(int streak, Integer chosenSeconds) {
        return chosenSeconds == null ? timeLimit(streak) : chosenSeconds * 1000;
    }
    public static int score(long responseMs, int limit, int streakBefore) {
        int speed = (int) Math.floor(50d * Math.max(0, Math.min(1, 1 - (double) responseMs / limit)));
        return 100 + speed + Math.min(streakBefore + 1, 20) * 5;
    }
    public enum State { READY, PLAYING, ANSWER_FEEDBACK, PAUSED, FINISHED, ABANDONED, EXPIRED }
    public enum Outcome { PENDING, ACTIVE, CORRECT, WRONG, TIMEOUT }
    public enum Filter { ALL, LEARNING, STARRED, RETRY }
}
