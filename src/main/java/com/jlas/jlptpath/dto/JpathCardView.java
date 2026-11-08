package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathStepKind;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] One step of a study session as the study page shows it. Card steps carry the item; check steps
 * (MICRO_CHECK, GROUP_EXERCISE) carry {@code checkKind} and, once issued, {@code checkToken}, with every content field
 * null. RETYPE hides {@code reading}/{@code romaji} (and the audio and example furigana, which would give the answer).
 * {@code examples} = MAIN ones, plus one EXTRA when {@code hard}; {@code confusables} only when {@code hard};
 * {@code intervalDays} = days per button (null for grammar and checks); {@code topic} = the topic title.
 */
public record JpathCardView(JpathItemType itemType, String code, JpathStepKind kind, boolean repeat, boolean notebook,
                            boolean hard, String level, String expression, String reading, String romaji,
                            String meaning, String wordType, String topic, List<JpathExampleView> examples,
                            String mnemonic, boolean mnemonicAutoOpen, List<JpathCompoundView> compounds,
                            List<JpathKanjiInWordView> kanjiInWord, List<JpathCompoundView> confusables,
                            String audioUrl, Map<JpathRating, Integer> intervalDays, JpathKanjiFacts kanji,
                            JpathGrammarFacts grammar, JpathKanaFacts kana, JpathCheckKind checkKind,
                            String checkToken) {
}
