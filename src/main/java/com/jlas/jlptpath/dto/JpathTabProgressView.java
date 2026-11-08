package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;

/**
 * [JLAS-W0108] Progress of one tab at one level: learnable total, counted items, floor percent, KNOWN, NOTEBOOK and
 * LEARNING items. Grammar has no notebook (0); KANA counts completed rows of 36 (known/notebook/learning 0).
 */
public record JpathTabProgressView(JpathTab tab, int total, int counted, int percent, int known, int notebook,
                                   int learning) {
}
