package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathItemType;
import java.util.List;

/**
 * [JLAS-W0108] Detail of one visible item for the list drawer. Holds no content status, answer or check data;
 * {@code state} is NEW (no learner row), LEARNING, NOTEBOOK or KNOWN. {@code compounds} only for kanji (sort order),
 * {@code kanjiInWord} only for words, {@code confusables} only for words and kanji.
 */
public record JpathItemDetailView(JpathItemType itemType, String code, String level, String expression, String reading,
                                  String romaji, String meaning, String wordType, String topic,
                                  List<JpathExampleView> examples, String mnemonic, List<JpathCompoundView> compounds,
                                  List<JpathKanjiInWordView> kanjiInWord, List<JpathCompoundView> confusables,
                                  JpathKanjiFacts kanji, JpathGrammarFacts grammar, JpathKanaFacts kana, String state,
                                  boolean hard, Long dictionaryEntryId, String audioUrl) {
}
