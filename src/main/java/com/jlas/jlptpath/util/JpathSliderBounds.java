package com.jlas.jlptpath.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSliderValues;
import com.jlas.jlptpath.enums.JpathTab;

/**
 * [JLAS-W0108] Slider limits per level (policy #3, step 1): N5, N4: V 5–50, K 2–20, G 1–5; N3, N2, N1: V 5–50,
 * K 2–15, G 1–4.
 */
public final class JpathSliderBounds {

    private JpathSliderBounds() {
    }

    public static int min(String level, JpathTab tab) {
        isLowerLevel(level); // validates the level
        return switch (tab) {
            case VOCAB -> 5;
            case KANJI -> 2;
            case GRAMMAR -> 1;
            default -> throw new IllegalArgumentException("No slider for tab " + tab);
        };
    }

    public static int max(String level, JpathTab tab) {
        boolean lower = isLowerLevel(level);
        return switch (tab) {
            case VOCAB -> 50;
            case KANJI -> lower ? 20 : 15;
            case GRAMMAR -> lower ? 5 : 4;
            default -> throw new IllegalArgumentException("No slider for tab " + tab);
        };
    }

    public static int clamp(String level, JpathTab tab, int value) {
        return Math.max(min(level, tab), Math.min(max(level, tab), value));
    }

    /** Rejects (never clamps) values a learner sent outside the level's limits. */
    public static void requireInRange(String level, JpathSliderValues values) {
        for (JpathTab tab : new JpathTab[] {JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR}) {
            int v = values.get(tab);
            if (v < min(level, tab) || v > max(level, tab)) {
                throw new ApiException(ErrorCode.JPATH_SLIDER_OUT_OF_RANGE);
            }
        }
    }

    /** N5/N4 use the wider limits; N3/N2/N1 the narrower ones; anything else is a programming error. */
    private static boolean isLowerLevel(String level) {
        if (level == null) {
            throw new IllegalArgumentException("Level is required");
        }
        return switch (level) {
            case "N5", "N4" -> true;
            case "N3", "N2", "N1" -> false;
            default -> throw new IllegalArgumentException("Unknown level " + level);
        };
    }
}
