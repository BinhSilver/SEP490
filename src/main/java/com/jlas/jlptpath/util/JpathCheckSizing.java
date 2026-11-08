package com.jlas.jlptpath.util;

import java.util.ArrayList;
import java.util.List;

/** [JLAS-W0108] Check sizes, pass mark and timers (policy #17, #18, #20, #21). */
public final class JpathCheckSizing {

    /** A check passes at this score or higher. */
    public static final int PASS_PERCENT = 80;
    /** Answering faster than this per unit marks the check {@code too_fast}. */
    public static final int TOO_FAST_MS_PER_UNIT = 1000;
    /** Vocabulary/kanji quiz timer when the learner turned it on. */
    public static final int VK_TIMER_SEC = 15;

    private static final int MAX_BOARD_PAIRS = 5;
    private static final int KANA_BOARD_MAX = 5;

    private JpathCheckSizing() {
    }

    /** Pairs on the vocabulary (and kanji) board: min(5, n). */
    public static int vocabBoardPairs(int n) {
        return Math.max(0, Math.min(MAX_BOARD_PAIRS, n));
    }

    /** clamp(ceil(0.3n), 4, 8); 0 when there is nothing to ask. */
    public static int vocabQuizCount(int n) {
        return n <= 0 ? 0 : clamp((3 * n + 9) / 10, 4, 8);
    }

    /** clamp(ceil(0.75n), 4, 8); 0 when there is nothing to ask. */
    public static int kanjiQuizCount(int n) {
        return n <= 0 ? 0 : clamp((3 * n + 3) / 4, 4, 8);
    }

    /** clamp(2n, 4, 10); 0 when there is nothing to ask. */
    public static int grammarQuestionCount(int n) {
        return n <= 0 ? 0 : clamp(2 * n, 4, 10);
    }

    /** ceil(c/5) boards of even size, the first ones one larger: 12 → [4,4,4], 7 → [4,3]. */
    public static List<Integer> kanaBoardSizes(int c) {
        List<Integer> out = new ArrayList<>();
        if (c <= 0) {
            return out;
        }
        int k = (c + KANA_BOARD_MAX - 1) / KANA_BOARD_MAX;
        for (int i = 0; i < k; i++) {
            out.add(c / k + (i < c % k ? 1 : 0));
        }
        return out;
    }

    /** #20: 25 s (N5, N4) or 20 s (N3+) plus floor(max(0, length − 30) / 10) s. */
    public static int grammarTimerSec(String level, int promptLength) {
        int base = "N3".equals(level) || "N2".equals(level) || "N1".equals(level) ? 20 : 25;
        return base + Math.max(0, promptLength - 30) / 10;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
