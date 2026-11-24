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
                                     Integer modeGrammar, Integer readingScore, Integer listeningScore) {

    /** Before Q9 (no Reading / Listening scores). */
    public JpathSmartTestSnapshot(Long attemptId, String status, String goal, String abilityBand,
                                  String estimatedLevel, Integer vocabScore, Integer kanjiScore,
                                  Integer grammarScore, boolean partial, Integer modeVocab, Integer modeKanji,
                                  Integer modeGrammar) {
        this(attemptId, status, goal, abilityBand, estimatedLevel, vocabScore, kanjiScore, grammarScore, partial,
                modeVocab, modeKanji, modeGrammar, null, null);
    }

    /** Q9 (owner 24/11): Reading or Listening mastery below this suggests a third skill day each week. */
    public static final int WEAK_SKILL_PCT = 50;

    /** Q9: Reading or Listening was tested and is weak. */
    public boolean weakReadingOrListening() {
        return (readingScore != null && readingScore < WEAK_SKILL_PCT)
                || (listeningScore != null && listeningScore < WEAK_SKILL_PCT);
    }

    public JpathSmartTestSnapshot(Long attemptId, String status, String goal, String abilityBand, String estimatedLevel,
                                  Integer vocabScore, Integer kanjiScore, Integer grammarScore, boolean partial) {
        this(attemptId, status, goal, abilityBand, estimatedLevel, vocabScore, kanjiScore, grammarScore, partial,
                vocabScore, kanjiScore, grammarScore);
    }
}
