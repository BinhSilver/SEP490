package com.jlas.dictionary.util;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Utility hỗ trợ tìm kiếm và xử lý dữ liệu từ điển.
 * Quy tắc chuẩn hóa mirror đúng {@code scripts/dictionary_import/jlas_import/normalization.py}
 * (JLAS2-19) để khóa tìm kiếm khớp với normalized_expression/normalized_reading đã import.
 */
public final class DictionaryUtil {
    private DictionaryUtil() { }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ROMAJI = Pattern.compile("^[a-zA-Z][a-zA-Z '-]*$");

    /** Loại input đã nhận diện; khớp domain search_mode của DictionarySearchHistory. */
    public enum SearchMode { KANJI, HIRAGANA, KATAKANA, ROMAJI, VIETNAMESE }

    /** NFKC + gom whitespace + trim. */
    public static String normalizeText(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return WHITESPACE.matcher(normalized).replaceAll(" ").strip();
    }

    /** normalizeText() rồi chuyển Katakana sang Hiragana, giống reading key đã import. */
    public static String normalizeReading(String value) {
        String normalized = normalizeText(value);
        StringBuilder builder = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(cp -> builder.appendCodePoint(katakanaToHiragana(cp)));
        return builder.toString();
    }

    private static int katakanaToHiragana(int codepoint) {
        if ((codepoint >= 0x30A1 && codepoint <= 0x30F6) || codepoint == 0x30FD || codepoint == 0x30FE) {
            return codepoint - 0x60;
        }
        return codepoint;
    }

    public static boolean isKanjiCodepoint(int codepoint) {
        return (codepoint >= 0x3400 && codepoint <= 0x4DBF)
                || (codepoint >= 0x4E00 && codepoint <= 0x9FFF)
                || (codepoint >= 0xF900 && codepoint <= 0xFAFF)
                || (codepoint >= 0x20000 && codepoint <= 0x2FA1F)
                || (codepoint >= 0x30000 && codepoint <= 0x323AF);
    }

    public static boolean containsKanji(String value) {
        return value != null && value.codePoints().anyMatch(DictionaryUtil::isKanjiCodepoint);
    }

    public static boolean isAllHiragana(String value) {
        return value != null && !value.isEmpty()
                && value.codePoints().allMatch(cp -> cp >= 0x3041 && cp <= 0x309F);
    }

    public static boolean isAllKatakana(String value) {
        return value != null && !value.isEmpty()
                && value.codePoints().allMatch(cp -> (cp >= 0x30A1 && cp <= 0x30FF));
    }

    public static boolean isRomaji(String value) {
        return value != null && ROMAJI.matcher(value).matches();
    }

    /**
     * Phân loại một query ĐÃ normalize (qua {@link #normalizeText}).
     * Kanji được ưu tiên trước vì một từ có thể lẫn Kanji + Kana (ví dụ 食べる).
     */
    public static SearchMode classify(String normalizedQuery) {
        if (containsKanji(normalizedQuery)) return SearchMode.KANJI;
        if (isAllHiragana(normalizedQuery)) return SearchMode.HIRAGANA;
        if (isAllKatakana(normalizedQuery)) return SearchMode.KATAKANA;
        if (isRomaji(normalizedQuery)) return SearchMode.ROMAJI;
        return SearchMode.VIETNAMESE;
    }
}
