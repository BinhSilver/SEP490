package com.jlas.jlptpath.util;

import java.text.Normalizer;

/**
 * [JLAS-W0108] Match key of a grammar pattern, used to map a Smart Test grammar point onto a path pattern:
 * NFKC, drop parenthesised parts ("来(く)る", "〜上（で）"), keep only kana/kanji (placeholders such as V, N, A,
 * "thể ます", "+", spaces and 〜/・ are dropped), katakana → hiragana. A key shorter than 2 characters is too weak to
 * match on and comes back empty.
 */
public final class JpathGrammarKey {

    private JpathGrammarKey() {
    }

    public static String of(String pattern) {
        if (pattern == null) {
            return "";
        }
        String text = Normalizer.normalize(pattern, Normalizer.Form.NFKC).replaceAll("\\([^)]*\\)", "");
        StringBuilder key = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (cp == '〜' || cp == '～' || cp == '・') {
                return;
            }
            if (cp >= 0x30A1 && cp <= 0x30F6) {
                key.appendCodePoint(cp - 0x60);
            } else if ((cp >= 0x3041 && cp <= 0x309F) || (cp >= 0x30F7 && cp <= 0x30FF)
                    || (cp >= 0x4E00 && cp <= 0x9FFF) || cp == 0x3005) {
                key.appendCodePoint(cp);
            }
        });
        return key.codePointCount(0, key.length()) >= 2 ? key.toString() : "";
    }
}
