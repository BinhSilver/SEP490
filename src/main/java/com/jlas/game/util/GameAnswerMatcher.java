package com.jlas.game.util;

import com.jlas.game.enums.DeckField;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chấm đáp án gõ tay (Air Defence). Không gọi AI trong lúc chơi: chỉ chuẩn hoá Unicode, đổi romaji
 * sang hiragana, bỏ dấu tiếng Việt và chấp nhận các biến thể trường âm (gakko = がっこう).
 * Client có bản TypeScript cùng luật để bắn laser ngay; server dùng bản này để kiểm lại.
 */
public final class GameAnswerMatcher {

    private static final Pattern VARIANT_SPLIT = Pattern.compile("[、,，;；/／|｜]");
    private static final Pattern PARENTHESES = Pattern.compile("[(（\\[【][^)）\\]】]*[)）\\]】]");
    private static final Pattern KANA_NOISE = Pattern.compile("[\\s・･.。、,，~〜～!！?？「」『』\"'‐-]");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern LATIN = Pattern.compile("[a-z]");
    private static final Map<String, String> ROMAJI = buildRomaji();
    private static final Map<Character, Character> VOWEL_OF = buildVowels();

    private GameAnswerMatcher() { }

    public static boolean matches(DeckField field, String expected, String raw) {
        if (expected == null || raw == null || raw.isBlank()) {
            return false;
        }
        return switch (field) {
            case KANJI -> matchesKana(expected, raw, false);
            case HIRAGANA -> matchesKana(expected, raw, true);
            case MEANING, HAN_VIET -> matchesVietnamese(expected, raw);
        };
    }

    // ------------------------------------------------------------------ kana

    private static boolean matchesKana(String expected, String raw, boolean allowRomaji) {
        String input = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        if (allowRomaji && LATIN.matcher(input).find()) {
            input = romajiToHiragana(input);
        }
        String inputKey = kanaKey(input);
        if (inputKey.isEmpty()) {
            return false;
        }
        String inputLoose = looseKana(inputKey);
        for (String variant : variants(expected)) {
            String key = kanaKey(variant);
            if (key.isEmpty()) {
                continue;
            }
            if (key.equals(inputKey) || looseKana(key).equals(inputLoose)) {
                return true;
            }
        }
        return false;
    }

    /** NFKC + chữ thường + katakana → hiragana, bỏ khoảng trắng và dấu câu. Kanji giữ nguyên. */
    static String kanaKey(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c >= 'ァ' && c <= 'ヶ') {
                out.append((char) (c - 0x60));
            } else {
                out.append(c);
            }
        }
        return KANA_NOISE.matcher(out).replaceAll("");
    }

    /** Gộp trường âm: がっこう ≈ がっこ, せんせい ≈ せんせ, ー bị bỏ. */
    static String looseKana(String kana) {
        StringBuilder out = new StringBuilder(kana.length());
        Character previousVowel = null;
        for (int i = 0; i < kana.length(); i++) {
            char c = kana.charAt(i);
            if (c == 'ー') {
                continue;
            }
            Character vowel = VOWEL_OF.get(c);
            boolean pureVowel = c == 'あ' || c == 'い' || c == 'う' || c == 'え' || c == 'お';
            if (pureVowel && previousVowel != null && extendsVowel(previousVowel, vowel)) {
                continue;
            }
            out.append(c);
            previousVowel = vowel;
        }
        return out.toString();
    }

    private static boolean extendsVowel(char previous, Character current) {
        if (current == null) {
            return false;
        }
        return previous == current
                || (previous == 'o' && current == 'u')
                || (previous == 'e' && current == 'i');
    }

    /** Romaji (Hepburn/Kunrei/kiểu gõ bàn phím) → hiragana; ký tự không nhận ra được giữ nguyên. */
    public static String romajiToHiragana(String romaji) {
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

    // ------------------------------------------------------------ vietnamese

    private static boolean matchesVietnamese(String expected, String raw) {
        String input = vietKey(raw);
        if (input.isEmpty()) {
            return false;
        }
        for (String variant : variants(expected)) {
            if (input.equals(vietKey(variant))) {
                return true;
            }
        }
        return false;
    }

    /** Chữ thường, bỏ dấu (đ → d), bỏ dấu câu: "Con mèo!" → "con meo". */
    static String vietKey(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('đ', 'd');
        String stripped = Normalizer.normalize(normalized, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return NON_WORD.matcher(stripped).replaceAll(" ").trim();
    }

    /** Cả chuỗi, từng nghĩa tách bởi , ; / và bản đã bỏ phần trong ngoặc. */
    static List<String> variants(String expected) {
        Set<String> result = new LinkedHashSet<>();
        String withoutNotes = PARENTHESES.matcher(expected).replaceAll(" ").trim();
        result.add(expected.trim());
        result.add(withoutNotes);
        for (String source : List.of(expected, withoutNotes)) {
            for (String part : VARIANT_SPLIT.split(source)) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                    result.add(PARENTHESES.matcher(trimmed).replaceAll(" ").trim());
                }
            }
        }
        result.remove("");
        return new ArrayList<>(result);
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

    private static Map<Character, Character> buildVowels() {
        Map<Character, Character> map = new HashMap<>();
        String[] rows = {
                "aあかさたなはまやらわがざだばぱぁゃ",
                "iいきしちにひみりぎじぢびぴぃ",
                "uうくすつぬふむゆるぐずづぶぷゔぅゅ",
                "eえけせてねへめれげぜでべぺぇ",
                "oおこそとのほもよろをごぞどぼぽぉょ"
        };
        for (String row : rows) {
            char vowel = row.charAt(0);
            for (int i = 1; i < row.length(); i++) {
                map.put(row.charAt(i), vowel);
            }
        }
        return map;
    }
}
