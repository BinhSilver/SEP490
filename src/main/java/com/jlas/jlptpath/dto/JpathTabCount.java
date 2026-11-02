package com.jlas.jlptpath.dto;

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
}
