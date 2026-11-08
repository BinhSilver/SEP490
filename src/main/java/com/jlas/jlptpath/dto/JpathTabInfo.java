package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;

/**
 * [JLAS-W0108] One tab of the open level. {@code open} = new items can be studied in it now; {@code placeholder}
 * = the tab is shown but not built yet (SKILL). KANA counts completed rows of 36.
 */
public record JpathTabInfo(JpathTab tab, boolean open, boolean placeholder, int percent, int known, int notebook,
                           int total) {
}
