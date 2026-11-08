package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Smart Test result on the confirmation screen; scores are rounded mastery (null when not tested),
 * {@code notebookCount} = wrong words/kanji that go into the notebook when the path is created.
 */
public record JpathSmartTestSummary(String abilityBand, Integer vocabScore, Integer kanjiScore, Integer grammarScore,
        int notebookCount, boolean partial) {
}
