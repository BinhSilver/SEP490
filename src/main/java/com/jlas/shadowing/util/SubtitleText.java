package com.jlas.shadowing.util;

/**
 * Normalize Japanese subtitle text from STT / AI.
 * Deepgram (and similar) often glues the previous utterance's trailing
 * {@code 。} onto the start of the next cue; models then copy it into translation.
 */
public final class SubtitleText {

    /** Remove caption metadata before translating it as if it were spoken words. */
    public static String cleanImportedCue(String text) {
        if (text == null) return "";
        String s = text.replaceAll("[\\p{Co}\\uFFFD\\u200B\\uFEFF]", "").trim();
        if (s.matches("[♪♫\\s]+") || s.matches("[\\[【(（](?:[^\\]】)）]*(?:音|声|どよめき|笑い|音楽|拍手)|Music|Applause)[\\]】)）]")) return "";
        // A parenthesized name before dialogue is a speaker label, not dialogue.
        s = s.replaceAll("[(（][^()（）]{1,32}[)）](?=[^\\s()（）])", "");
        s = s.replaceFirst("^[(（][^()（）]{1,32}[)）]\\s+(?=.+)", "");
        return s.replaceAll("[＜＞]", "").replaceAll("ーー+$", "").trim();
    }

    /** Leading sentence-final / filler punctuation that must not start a cue. */
    private static final String LEADING_PUNCT_CLASS = "[\\s。．.、，,;；:：・…‥]+";

    private SubtitleText() {
    }

    /**
     * JP line for storage / AI: strip spaces and orphan leading punctuation.
     */
    public static String normalizeJapanese(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace(" ", "").trim();
        return stripLeadingSentencePunctuation(s);
    }

    /**
     * Strip leading 。 . 、 etc. (and whitespace) from any field (text, furigana, translation).
     */
    public static String stripLeadingSentencePunctuation(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return text.replaceFirst("^" + LEADING_PUNCT_CLASS, "");
    }

    /**
     * Clean AI furigana/translation so they never reintroduce a leading 。
     * that was (or should have been) removed from the source JP line.
     */
    public static String sanitizeAiField(String field) {
        if (field == null) {
            return "";
        }
        return stripLeadingSentencePunctuation(field.trim());
    }
}
