package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathRetypeVerdict;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * [JLAS-W0108] Grades one dictation blank (policy #35). Text is normalized with {@link JpathKanaText#normalize}
 * (katakana → hiragana of NFKC, as {@code normalized_*}) and whitespace and punctuation are dropped; ASCII input is
 * read as romaji. A match with the answer or any accepted form (kanji or kana) is EXACT; a match under the minor-slip
 * rule of {@link JpathKanaText#retypeVerdict} (ん, っ, long vowel) that is at most one character off is MINOR; anything
 * else, and blank input, is WRONG. No state, no I/O.
 */
public final class JpathDictationGrader {

    /** Whitespace, punctuation (incl. ・) and symbols such as ～; ー is a letter modifier and stays. */
    private static final Pattern IGNORED = Pattern.compile("[\\s\\p{P}\\p{S}]+");
    /** Policy #35: a minor slip may be at most this many characters off. */
    private static final int MAX_MINOR_CHARS = 1;

    private JpathDictationGrader() {
    }

    /** Best verdict of {@code input} against {@code answer} and every {@code accept} form. */
    public static JpathRetypeVerdict grade(String input, String answer, List<String> accept) {
        String typed = clean(input);
        if (typed.chars().anyMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
            typed = clean(JpathKanaText.romajiToHiragana(typed));
        }
        if (typed.isEmpty()) {
            return JpathRetypeVerdict.WRONG;
        }
        List<String> forms = new ArrayList<>();
        forms.add(answer);
        if (accept != null) {
            forms.addAll(accept);
        }
        JpathRetypeVerdict best = JpathRetypeVerdict.WRONG;
        for (String form : forms) {
            String expected = clean(form);
            if (expected.isEmpty()) {
                continue;
            }
            JpathRetypeVerdict verdict = JpathKanaText.retypeVerdict(expected, typed);
            if (verdict == JpathRetypeVerdict.EXACT) {
                return verdict;
            }
            if (verdict == JpathRetypeVerdict.MINOR
                    && Math.abs(expected.codePointCount(0, expected.length())
                            - typed.codePointCount(0, typed.length())) <= MAX_MINOR_CHARS) {
                best = JpathRetypeVerdict.MINOR;
            }
        }
        return best;
    }

    /** {@link JpathKanaText#normalize} then drop whitespace, punctuation and symbols; null → "". */
    private static String clean(String text) {
        return IGNORED.matcher(JpathKanaText.normalize(text)).replaceAll("");
    }
}
