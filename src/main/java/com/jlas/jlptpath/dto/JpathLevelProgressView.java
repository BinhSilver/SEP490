package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathStage;
import java.util.List;

/**
 * [JLAS-W0108] Progress of a level: percent = floor(100 x exact mean of the VOCAB/KANJI/GRAMMAR ratios); stage of the
 * level in the active path (no row: PASSED below the open level, else STUDY; STUDY without a path); tabs VOCAB, KANJI, GRAMMAR, then KANA when the path has the kana tab;
 * totals over the three level tabs.
 */
public record JpathLevelProgressView(String level, int percent, JpathStage stage, List<JpathTabProgressView> tabs,
                                     int knownTotal, int notebookTotal, int total) {
}
