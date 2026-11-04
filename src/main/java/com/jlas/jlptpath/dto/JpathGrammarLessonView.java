package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] The grammar lesson page of one pattern. Carries no practice question and no answer:
 * {@code practiceAvailable} = at least one visible ORDER and one visible FILL question ("Luyện ngay"),
 * {@code extraAvailable} = at least three visible EXTRA questions ("Luyện thêm"). {@code prevCode}/{@code nextCode}
 * walk the visible patterns of the level in teaching order (null at either end). {@code attached} = the secondary
 * patterns attached to this one (shown in its lesson, never learned on their own).
 */
public record JpathGrammarLessonView(String code, String level, int unitNo, String unitTitle, int indexInUnit,
                                     int unitSize, String pattern, List<String> variants, String meaning,
                                     String formation, String usage, String nuance, String commonMistakes,
                                     List<JpathGrammarRowView> related, List<JpathExampleView> examples,
                                     boolean practiceAvailable, boolean extraAvailable, String state,
                                     boolean learnable, String prevCode, String nextCode,
                                     List<JpathAttachedPatternView> attached) {
}
