package com.jlas.jlptpath.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;

/**
 * [JLAS-W0108] Minute model of the JLPT path: minutes per unit (policy #1), slider recommendation from daily
 * minutes (#2) and estimated minutes of a study day (#4). Pure functions.
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
        double kana = KANA_ROWS_PER_DAY * KANA_ROW_MIN + VOCAB_MIN * kanaPhaseVocab(minutes);
        return (int) Math.round(mode == JpathStudyMode.SPLIT ? kana + kanjiGrammar : kana);
    }

    public static void requireMinuteOption(int minutes) {
        if (!MINUTE_OPTIONS.contains(minutes)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Số phút mỗi ngày không hợp lệ.");
        }
    }
}
