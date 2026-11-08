package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.entity.JpathUserSettings;

/**
 * [JLAS-W0108] Display settings the study pages need. {@code romajiOn} resolves {@code romajiMode}: AUTO = on at N5
 * (also without a path), off from N4 (policy #32); ON / OFF as set.
 */
public record JpathDisplaySettings(boolean furiganaHiddenAll, String romajiMode, boolean romajiOn, String fontSize,
        boolean soundOn, boolean autoplayAudio, boolean quizTimerOn, boolean grammarTimerOn, String vocabListView,
        boolean tourDone, boolean swipeHintDone) {

    /**
     * @param s         the learner's row, null = defaults (vocabulary/kanji timer off, grammar timer on)
     * @param openLevel open level of the active path, null without a path
     */
    public static JpathDisplaySettings from(JpathUserSettings s, String openLevel) {
        JpathUserSettings v = s != null ? s : JpathUserSettings.builder().build();
        String mode = v.getRomajiMode() == null ? "AUTO" : v.getRomajiMode();
        boolean romaji = switch (mode) {
            case "ON" -> true;
            case "OFF" -> false;
            default -> openLevel == null || "N5".equals(openLevel);
        };
        return new JpathDisplaySettings(v.isFuriganaHiddenAll(), mode, romaji, v.getFontSize(), v.isSoundOn(),
                v.isAutoplayAudio(), v.isQuizTimerOn(), v.isGrammarTimerOn(), v.getVocabListView(),
                v.getTourDoneAt() != null, v.getSwipeHintDoneAt() != null);
    }
}
