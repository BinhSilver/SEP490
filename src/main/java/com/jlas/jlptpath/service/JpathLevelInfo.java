package com.jlas.jlptpath.service;

/**
 * [JLAS-W0108] One JLPT level of the path catalogue.
 *
 * @param level        N5..N1
 * @param sortOrder    N5 = 1 … N1 = 5
 * @param released     learners can study it (released in the catalogue, or forced locally)
 * @param comingSoon   not released yet (shown as "sắp có")
 * @param vocabCount   learner-visible words when released, planned words otherwise
 * @param kanjiCount   learner-visible kanji when released, planned kanji otherwise
 * @param grammarCount learner-visible grammar patterns when released, planned patterns otherwise
 */
public record JpathLevelInfo(String level, int sortOrder, boolean released, boolean comingSoon, int vocabCount,
                             int kanjiCount, int grammarCount) {
}
