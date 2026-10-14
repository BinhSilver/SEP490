package com.jlas.game.util;

import com.jlas.game.enums.DeckField;
import com.jlas.common.util.RomajiKana;

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

    /** Shared converter; the game-specific permissive scoring rules remain local. */
    public static String romajiToHiragana(String romaji) {
        return RomajiKana.toHiragana(romaji);
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
