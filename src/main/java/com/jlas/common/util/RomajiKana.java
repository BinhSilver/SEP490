package com.jlas.common.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Local, deterministic Hepburn/Kunrei and IME transliteration; unknown characters stay intact. */
public final class RomajiKana {
    private static final Map<String, String> ROMAJI = buildRomaji();

    private RomajiKana() { }

    /** Romaji (Hepburn/Kunrei/kiểu gõ bàn phím) → hiragana; ký tự không nhận ra được giữ nguyên. */
    public static String toHiragana(String romaji) {
        String s = romaji.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            if (c == '-') {
                out.append('ー');
                i++;
                continue;
            }
            if (c == 'n') {
                if (next == '\'') {
                    out.append('ん');
                    i += 2;
                    continue;
                }
                if (next == 'n') {
                    char after = i + 2 < s.length() ? s.charAt(i + 2) : 0;
                    // "nni" = ん + に; "nn" đứng trước phụ âm/cuối từ = ん (kiểu gõ bàn phím).
                    out.append('ん');
                    i += isVowel(after) || after == 'y' ? 1 : 2;
                    continue;
                }
                if (next == 0 || (!isVowel(next) && next != 'y')) {
                    out.append('ん');
                    i++;
                    continue;
                }
            }
            if (c >= 'a' && c <= 'z' && !isVowel(c) && c == next) {
                out.append('っ');
                i++;
                continue;
            }
            if (c == 't' && next == 'c' && i + 2 < s.length() && s.charAt(i + 2) == 'h') {
                out.append('っ');
                i++;
                continue;
            }
            boolean matched = false;
            for (int length = 4; length >= 1; length--) {
                if (i + length > s.length()) {
                    continue;
                }
                String kana = ROMAJI.get(s.substring(i, i + length));
                if (kana != null) {
                    out.append(kana);
                    i += length;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o';
    }

    private static Map<String, String> buildRomaji() {
        Map<String, String> map = new HashMap<>();
        String[][] rows = {
                {"a", "あ"}, {"i", "い"}, {"u", "う"}, {"e", "え"}, {"o", "お"},
                {"ka", "か"}, {"ki", "き"}, {"ku", "く"}, {"ke", "け"}, {"ko", "こ"},
                {"kya", "きゃ"}, {"kyu", "きゅ"}, {"kyo", "きょ"},
                {"sa", "さ"}, {"shi", "し"}, {"si", "し"}, {"su", "す"}, {"se", "せ"}, {"so", "そ"},
                {"sha", "しゃ"}, {"shu", "しゅ"}, {"sho", "しょ"}, {"she", "しぇ"},
                {"sya", "しゃ"}, {"syu", "しゅ"}, {"syo", "しょ"},
                {"ta", "た"}, {"chi", "ち"}, {"ti", "ち"}, {"tsu", "つ"}, {"tu", "つ"}, {"te", "て"}, {"to", "と"},
                {"cha", "ちゃ"}, {"chu", "ちゅ"}, {"cho", "ちょ"}, {"che", "ちぇ"},
                {"tya", "ちゃ"}, {"tyu", "ちゅ"}, {"tyo", "ちょ"}, {"cya", "ちゃ"}, {"cyu", "ちゅ"}, {"cyo", "ちょ"},
                {"na", "な"}, {"ni", "に"}, {"nu", "ぬ"}, {"ne", "ね"}, {"no", "の"},
                {"nya", "にゃ"}, {"nyu", "にゅ"}, {"nyo", "にょ"},
                {"ha", "は"}, {"hi", "ひ"}, {"fu", "ふ"}, {"hu", "ふ"}, {"he", "へ"}, {"ho", "ほ"},
                {"hya", "ひゃ"}, {"hyu", "ひゅ"}, {"hyo", "ひょ"},
                {"fa", "ふぁ"}, {"fi", "ふぃ"}, {"fe", "ふぇ"}, {"fo", "ふぉ"},
                {"ma", "ま"}, {"mi", "み"}, {"mu", "む"}, {"me", "め"}, {"mo", "も"},
                {"mya", "みゃ"}, {"myu", "みゅ"}, {"myo", "みょ"},
                {"ya", "や"}, {"yu", "ゆ"}, {"yo", "よ"},
                {"ra", "ら"}, {"ri", "り"}, {"ru", "る"}, {"re", "れ"}, {"ro", "ろ"},
                {"rya", "りゃ"}, {"ryu", "りゅ"}, {"ryo", "りょ"},
                {"la", "ら"}, {"li", "り"}, {"lu", "る"}, {"le", "れ"}, {"lo", "ろ"},
                {"wa", "わ"}, {"wo", "を"}, {"wi", "うぃ"}, {"we", "うぇ"},
                {"ga", "が"}, {"gi", "ぎ"}, {"gu", "ぐ"}, {"ge", "げ"}, {"go", "ご"},
                {"gya", "ぎゃ"}, {"gyu", "ぎゅ"}, {"gyo", "ぎょ"},
                {"za", "ざ"}, {"ji", "じ"}, {"zi", "じ"}, {"zu", "ず"}, {"ze", "ぜ"}, {"zo", "ぞ"},
                {"ja", "じゃ"}, {"ju", "じゅ"}, {"jo", "じょ"}, {"je", "じぇ"},
                {"jya", "じゃ"}, {"jyu", "じゅ"}, {"jyo", "じょ"}, {"zya", "じゃ"}, {"zyu", "じゅ"}, {"zyo", "じょ"},
                {"da", "だ"}, {"di", "ぢ"}, {"du", "づ"}, {"dzu", "づ"}, {"de", "で"}, {"do", "ど"},
                {"dya", "ぢゃ"}, {"dyu", "ぢゅ"}, {"dyo", "ぢょ"},
                {"ba", "ば"}, {"bi", "び"}, {"bu", "ぶ"}, {"be", "べ"}, {"bo", "ぼ"},
                {"bya", "びゃ"}, {"byu", "びゅ"}, {"byo", "びょ"},
                {"pa", "ぱ"}, {"pi", "ぴ"}, {"pu", "ぷ"}, {"pe", "ぺ"}, {"po", "ぽ"},
                {"pya", "ぴゃ"}, {"pyu", "ぴゅ"}, {"pyo", "ぴょ"},
                {"va", "ゔぁ"}, {"vi", "ゔぃ"}, {"vu", "ゔ"}, {"ve", "ゔぇ"}, {"vo", "ゔぉ"},
                {"xa", "ぁ"}, {"xi", "ぃ"}, {"xu", "ぅ"}, {"xe", "ぇ"}, {"xo", "ぉ"},
                {"xya", "ゃ"}, {"xyu", "ゅ"}, {"xyo", "ょ"}, {"xtu", "っ"}, {"xtsu", "っ"}, {"ltu", "っ"},
                {"ltsu", "っ"}
        };
        for (String[] row : rows) {
            map.put(row[0], row[1]);
        }
        return map;
    }

}
