package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] What the JLPT path reads from one Smart Test attempt. Scores are the rounded VOCABULARY / KANJI /
 * GRAMMAR mastery scores (null when the skill was not tested); an IN_PROGRESS attempt carries only id and status.
 */
public record JpathSmartTestSnapshot(Long attemptId, String status, String goal, String abilityBand,
                                     String estimatedLevel, Integer vocabScore, Integer kanjiScore,
                                     Integer grammarScore, boolean partial) {
}
