package com.jlas.kaiwa.util;

import java.text.Normalizer;

/** Shared kanji detection and kana conversion for learning annotations. */
public final class JapaneseText {
    private JapaneseText() {}

    public static boolean hasKanji(String text) {
        return text != null && text.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN || cp == '々' || cp == '〆');
    }

    public static String hiraganaReading(String reading) {
        if (reading == null || reading.isBlank() || "*".equals(reading)) return "";
        String normalized = Normalizer.normalize(reading.trim(), Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder();
        normalized.codePoints().forEach(cp -> result.appendCodePoint(
                cp >= 0x30A1 && cp <= 0x30F6 ? cp - 0x60 : cp));
        String kana = result.toString();
        return kana.codePoints().allMatch(cp -> (cp >= 0x3041 && cp <= 0x3096)
                || cp == 0x309D || cp == 0x309E || cp == 0x30FC) ? kana : "";
    }
}
