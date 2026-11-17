package com.jlas.shadowing.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Romaji typed on a Latin keyboard to hiragana, for search only ("arigatou" → ありがとう, "kitte" → きって,
 * "konnichiwa" → こんにちわ). Hepburn and Kunrei spellings both work. Returns null when the word is not romaji,
 * so ordinary Vietnamese or English words are left alone.
 */
public final class RomajiKana {
    private static final Map<String, String> TABLE = new HashMap<>();
    private static final String VOWELS = "aiueo";

    static {
        String[][] rows = {
                {"a", "あ"}, {"i", "い"}, {"u", "う"}, {"e", "え"}, {"o", "お"},
                {"ka", "か"}, {"ki", "き"}, {"ku", "く"}, {"ke", "け"}, {"ko", "こ"},
                {"ga", "が"}, {"gi", "ぎ"}, {"gu", "ぐ"}, {"ge", "げ"}, {"go", "ご"},
                {"sa", "さ"}, {"shi", "し"}, {"si", "し"}, {"su", "す"}, {"se", "せ"}, {"so", "そ"},
                {"za", "ざ"}, {"ji", "じ"}, {"zi", "じ"}, {"zu", "ず"}, {"ze", "ぜ"}, {"zo", "ぞ"},
                {"ta", "た"}, {"chi", "ち"}, {"ti", "ち"}, {"tsu", "つ"}, {"tu", "つ"}, {"te", "て"}, {"to", "と"},
                {"da", "だ"}, {"di", "ぢ"}, {"du", "づ"}, {"de", "で"}, {"do", "ど"},
                {"na", "な"}, {"ni", "に"}, {"nu", "ぬ"}, {"ne", "ね"}, {"no", "の"},
                {"ha", "は"}, {"hi", "ひ"}, {"fu", "ふ"}, {"hu", "ふ"}, {"he", "へ"}, {"ho", "ほ"},
                {"ba", "ば"}, {"bi", "び"}, {"bu", "ぶ"}, {"be", "べ"}, {"bo", "ぼ"},
                {"pa", "ぱ"}, {"pi", "ぴ"}, {"pu", "ぷ"}, {"pe", "ぺ"}, {"po", "ぽ"},
                {"ma", "ま"}, {"mi", "み"}, {"mu", "む"}, {"me", "め"}, {"mo", "も"},
                {"ya", "や"}, {"yu", "ゆ"}, {"yo", "よ"},
                {"ra", "ら"}, {"ri", "り"}, {"ru", "る"}, {"re", "れ"}, {"ro", "ろ"},
                {"wa", "わ"}, {"wo", "を"},
                {"sha", "しゃ"}, {"shu", "しゅ"}, {"sho", "しょ"}, {"she", "しぇ"},
                {"sya", "しゃ"}, {"syu", "しゅ"}, {"syo", "しょ"},
                {"ja", "じゃ"}, {"ju", "じゅ"}, {"jo", "じょ"}, {"je", "じぇ"},
                {"zya", "じゃ"}, {"zyu", "じゅ"}, {"zyo", "じょ"}, {"jya", "じゃ"}, {"jyu", "じゅ"}, {"jyo", "じょ"},
                {"cha", "ちゃ"}, {"chu", "ちゅ"}, {"cho", "ちょ"}, {"che", "ちぇ"},
                {"tya", "ちゃ"}, {"tyu", "ちゅ"}, {"tyo", "ちょ"},
                {"fa", "ふぁ"}, {"fi", "ふぃ"}, {"fe", "ふぇ"}, {"fo", "ふぉ"},
        };
        for (String[] row : rows) TABLE.put(row[0], row[1]);
        String[][] youon = {{"k", "き"}, {"g", "ぎ"}, {"n", "に"}, {"h", "ひ"}, {"b", "び"}, {"p", "ぴ"},
                {"m", "み"}, {"r", "り"}};
        for (String[] row : youon) {
            TABLE.put(row[0] + "ya", row[1] + "ゃ");
            TABLE.put(row[0] + "yu", row[1] + "ゅ");
            TABLE.put(row[0] + "yo", row[1] + "ょ");
        }
    }

    private RomajiKana() {
    }

    /** Hiragana for a romaji word, or null when it contains anything that is not romaji. */
    public static String toHiragana(String word) {
        if (word == null || word.isEmpty()) return null;
        String text = word.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c < 'a' || c > 'z') return null;
            // "n" before a consonant, an apostrophe or the end, and "nn", are ん.
            if (c == 'n') {
                char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
                char after = i + 2 < text.length() ? text.charAt(i + 2) : 0;
                if (next == 0 || next == '\'' || (VOWELS.indexOf(next) < 0 && next != 'y')) {
                    out.append('ん');
                    // "nn" + vowel ("konna"): the second n starts the next kana; otherwise "nn" / "n'" is one ん.
                    boolean nnThenKana = next == 'n' && (VOWELS.indexOf(after) >= 0 || after == 'y') && after != 0;
                    i += (next == 'n' && !nnThenKana) || next == '\'' ? 2 : 1;
                    continue;
                }
            }
            // A doubled consonant is a small っ ("kitte", "matcha").
            if (i + 1 < text.length() && VOWELS.indexOf(c) < 0 && c != 'n'
                    && (text.charAt(i + 1) == c || (c == 't' && text.charAt(i + 1) == 'c'))) {
                out.append('っ');
                i++;
                continue;
            }
            String kana = null;
            int used = 0;
            for (int len = 3; len >= 1 && kana == null; len--) {
                if (i + len <= text.length()) {
                    kana = TABLE.get(text.substring(i, i + len));
                    used = len;
                }
            }
            if (kana == null) return null;
            out.append(kana);
            i += used;
        }
        return out.toString();
    }
}
