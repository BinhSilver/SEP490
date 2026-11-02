package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] Progress of one tab: items counted towards progress, KNOWN items, NOTEBOOK items and the learnable
 * total (kana: completed rows of 36).
 */
public record JpathTabCount(long counted, long known, long notebook, long total) {

    /** floor(100 × counted / total), at most 100; 0 when the total is 0 (policy #16). */
    public int percent() {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(100, counted * 100 / total);
    }

    /**
     * Level % (policy #16): floor(100 x exact mean of the VOCAB, KANJI and GRAMMAR ratios counted/total); a tab with
     * total 0 (or missing) counts 0, a ratio caps at 1. The one level-% formula of the module.
     */
    public static int levelPercent(Map<JpathTab, JpathTabCount> counts) {
        List<JpathTab> tabs = List.of(JpathTab.VOCAB, JpathTab.KANJI, JpathTab.GRAMMAR);
        // Sum of counted/total over a common denominator keeps the mean exact.
        long numerator = 0;
        long denominator = 1;
        for (JpathTab tab : tabs) {
            JpathTabCount c = counts.get(tab);
            boolean empty = c == null || c.total() <= 0;
            long t = empty ? 1 : c.total();
            long n = empty ? 0 : Math.min(c.counted(), c.total());
            numerator = numerator * t + n * denominator;
            denominator *= t;
        }
        return (int) (numerator * 100 / (denominator * tabs.size()));
    }
}
