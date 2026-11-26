package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Smart Test result on the confirmation screen; scores are rounded mastery (null when not tested),
 * {@code estimatedLevel} = rounded average of the skill levels (the level the path opens at, before release capping),
 * {@code notebookCount} = wrong words/kanji/grammar that go into the notebook when the path is created.
 */
public record JpathSmartTestSummary(String abilityBand, String estimatedLevel, Integer vocabScore, Integer kanjiScore, Integer grammarScore,
        int notebookCount, boolean partial) {
}
