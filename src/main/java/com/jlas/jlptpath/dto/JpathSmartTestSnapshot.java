package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] What the JLPT path reads from one Smart Test attempt. Scores are the rounded VOCABULARY / KANJI /
 * GRAMMAR mastery scores (null when the skill was not tested); an IN_PROGRESS attempt carries only id and status.
 * The {@code mode*} scores feed the combined/split suggestion only (E2E-005): each skill's estimated level
 * counts 100 points above the level below it, and skills at the same level and band score alike.
 */
public record JpathSmartTestSnapshot(Long attemptId, String status, String goal, String abilityBand,
                                     String estimatedLevel, Integer vocabScore, Integer kanjiScore,
                                     Integer grammarScore, boolean partial, Integer modeVocab, Integer modeKanji,
                                     Integer modeGrammar) {

    public JpathSmartTestSnapshot(Long attemptId, String status, String goal, String abilityBand, String estimatedLevel,
                                  Integer vocabScore, Integer kanjiScore, Integer grammarScore, boolean partial) {
        this(attemptId, status, goal, abilityBand, estimatedLevel, vocabScore, kanjiScore, grammarScore, partial,
                vocabScore, kanjiScore, grammarScore);
    }
}
