package com.jlas.learning.service.impl;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Compares a typed answer with a kana answer key, accepting the same reading written in katakana,
 * hiragana or Hepburn romaji (コウ = こう = kou = kō), and the long-vowel mark (コー).
 */
final class KanaAnswerMatcher {

    private static final String VOWELS = "aiueo";
    private static final Map<String, String> ROMAJI = new HashMap<>();

    static {
        String[][] rows = {
                {"", "アイウエオ"}, {"k", "カキクケコ"}, {"g", "ガギグゲゴ"}, {"s", "サシスセソ"}, {"z", "ザジズゼゾ"},
                {"t", "タチツテト"}, {"d", "ダヂヅデド"}, {"n", "ナニヌネノ"}, {"h", "ハヒフヘホ"}, {"b", "バビブベボ"},
                {"p", "パピプペポ"}, {"m", "マミムメモ"}, {"r", "ラリルレロ"},
        };
        for (String[] row : rows) {
            for (int i = 0; i < 5; i++) {
                ROMAJI.put(row[0] + VOWELS.charAt(i), String.valueOf(row[1].charAt(i)));
            }
            if (!row[0].isEmpty() && !"d".equals(row[0])) {
                String i = String.valueOf(row[1].charAt(1));
                ROMAJI.put(row[0] + "ya", i + "ャ");
                ROMAJI.put(row[0] + "yu", i + "ュ");
                ROMAJI.put(row[0] + "yo", i + "ョ");
            }
        }
        ROMAJI.put("ya", "ヤ");
        ROMAJI.put("yu", "ユ");
        ROMAJI.put("yo", "ヨ");
        ROMAJI.put("wa", "ワ");
        ROMAJI.put("wo", "ヲ");
        ROMAJI.put("shi", "シ");
        ROMAJI.put("chi", "チ");
        ROMAJI.put("tsu", "ツ");
        ROMAJI.put("fu", "フ");
        ROMAJI.put("ji", "ジ");
        for (String[] palatal : new String[][] {{"sh", "シ"}, {"ch", "チ"}, {"j", "ジ"}}) {
            ROMAJI.put(palatal[0] + "a", palatal[1] + "ャ");
            ROMAJI.put(palatal[0] + "u", palatal[1] + "ュ");
            ROMAJI.put(palatal[0] + "o", palatal[1] + "ョ");
        }
    }

    private KanaAnswerMatcher() {
    }

    static boolean matches(String expected, String given) {
        if (expected == null || given == null || given.isBlank()) {
            return false;
        }
        if (expected.trim().equalsIgnoreCase(given.trim())) {
            return true;
        }
        String key = toKatakana(expected);
        return key != null && !key.isEmpty() && key.equals(toKatakana(given));
    }

    /** Katakana form of a kana or romaji answer, or null when it is neither. */
    static String toKatakana(String text) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s・.\\-']", "")
                .replace("ō", "ou").replace("ū", "uu").replace("ā", "aa").replace("ē", "ei").replace("ī", "ii");
        StringBuilder kana = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c >= 'ぁ' && c <= 'ゖ') {
                kana.append((char) (c + 0x60));
                i++;
            } else if ((c >= 'ァ' && c <= 'ヶ') || c == 'ー') {
                kana.append(c);
                i++;
            } else if (c >= 'a' && c <= 'z') {
                int used = romajiAt(value, i, kana);
                if (used == 0) {
                    return null;
                }
                i += used;
            } else {
                return null;
            }
        }
        return expandLongVowels(kana.toString());
    }

    /** Appends the katakana for the romaji at {@code i}; returns how many characters it used, or 0. */
    private static int romajiAt(String text, int i, StringBuilder out) {
        char c = text.charAt(i);
        boolean last = i + 1 == text.length();
        if (c == 'n' && (last || (VOWELS.indexOf(text.charAt(i + 1)) < 0 && text.charAt(i + 1) != 'y'))) {
            out.append('ン');
            return 1;
        }
        if (!last && VOWELS.indexOf(c) < 0 && (c == text.charAt(i + 1) || (c == 't' && text.charAt(i + 1) == 'c'))) {
            out.append('ッ');
            return 1;
        }
        for (int size = 3; size >= 1; size--) {
            if (i + size <= text.length()) {
                String kana = ROMAJI.get(text.substring(i, i + size));
                if (kana != null) {
                    out.append(kana);
                    return size;
                }
            }
        }
        return 0;
    }

    /** コー → コウ, セー → セイ: the long-vowel mark written as the vowel On readings use. */
    private static String expandLongVowels(String kana) {
        StringBuilder out = new StringBuilder(kana.length());
        for (int i = 0; i < kana.length(); i++) {
            char c = kana.charAt(i);
            if (c == 'ー' && i > 0) {
                char vowel = vowelOf(kana.charAt(i - 1));
                out.append(vowel == 'o' || vowel == 'u' ? 'ウ' : vowel == 'e' || vowel == 'i' ? 'イ' : 'ア');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static char vowelOf(char kana) {
        String[] columns = {"アカガサザタダナハバパマヤラワャ", "イキギシジチヂニヒビピミリ", "ウクグスズツヅヌフブプムユルュ",
                "エケゲセゼテデネヘベペメレ", "オコゴソゾトドノホボポモヨロヲョ"};
        for (int v = 0; v < columns.length; v++) {
            if (columns[v].indexOf(kana) >= 0) {
                return VOWELS.charAt(v);
            }
        }
        return ' ';
    }
}
