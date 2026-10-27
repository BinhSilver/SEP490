package com.jlas.shadowing.util;

import java.util.regex.Pattern;

/**
 * Normalize Japanese subtitle text from STT / AI.
 * Deepgram (and similar) often glues the previous utterance's trailing
 * {@code 。} onto the start of the next cue; models then copy it into translation.
 */
public final class SubtitleText {

    /**
     * A bracketed sound description ([音楽], (警報音), [ため息], 【拍手】…), YouTube's masked word [ __ ]
     * or a music note, anywhere in the cue.
     */
    private static final Pattern SOUND_TAG = Pattern.compile(
            "[\\[【(（](?:[^\\[\\]【】()（）]*(?:音|声|息|咳払い|咳|どよめき|笑い|音楽|拍手|歓声)|(?i:music|applause|laughter))[\\]】)）]"
                    + "|\\[\\s*_+\\s*]|[♪♫♩♬]");
    /** The same character five or more times in a row (digits excluded: 1000000 is a number, not a scream). */
    private static final Pattern LONG_RUN = Pattern.compile("([^\\d０-９\\s])\\1{4,}");
    private static final String PUNCTUATION = "[\\s\\p{P}ー〜～]";
    /** A letter (not a long-vowel mark: わーーーーい is a word) repeated five or more times. */
    private static final Pattern LONG_LETTER_RUN = Pattern.compile("([^\\d０-９\\s\\p{P}ー〜～])\\1{4,}");
    private static final Pattern REPEATED_RUN = Pattern.compile("([^\\d０-９\\s\\p{P}ー〜～])\\1{2,}");
    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-zＡ-Ｚａ-ｚ]{2,}");

    /** Remove caption metadata before translating it as if it were spoken words. */
    public static String cleanImportedCue(String text) {
        if (text == null) return "";
        String s = text.replaceAll("[\\p{Co}\\uFFFD\\u200B\\uFEFF]", "").trim();
        s = SOUND_TAG.matcher(s).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
        if (s.isEmpty() || isOnlyScream(s)) return "";
        s = LONG_RUN.matcher(s).replaceAll("$1$1$1");
        // A parenthesized name before dialogue is a speaker label, not dialogue.
        s = s.replaceAll("[(（][^()（）]{1,32}[)）](?=[^\\s()（）])", "");
        s = s.replaceFirst("^[(（][^()（）]{1,32}[)）]\\s+(?=.+)", "");
        return s.replaceAll("[＜＞]", "").replaceAll("ーー+$", "").trim();
    }

    /**
     * Second pass once caption fragments are joined into one line: い + いい + いいい only becomes a long run
     * after joining.
     */
    public static String tidyAssembledCue(String text) {
        if (text == null) return "";
        String s = text.trim();
        if (s.isEmpty() || isOnlyScream(s)) return "";
        return LONG_RUN.matcher(s).replaceAll("$1$1$1");
    }

    /** True when the line has a run of 3+ identical characters, i.e. a scream or a shortened recognition loop. */
    public static boolean hasRepeatedRun(String text) {
        return text != null && REPEATED_RUN.matcher(text).find();
    }

    /** True when the line has a Latin word (2+ letters) inside Japanese text: usually a misheard sound. */
    public static boolean hasLatinInJapanese(String text) {
        return text != null && JAPANESE.matcher(text).find() && LATIN_WORD.matcher(text).find();
    }

    /** うおおおおお or あああああ: one long run and nothing else to study. */
    private static boolean isOnlyScream(String text) {
        if (!LONG_LETTER_RUN.matcher(text).find()) return false;
        return text.replaceAll(PUNCTUATION, "").codePoints().distinct().count() <= 2;
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
