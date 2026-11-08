package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathRetypeVerdict;
import java.text.Normalizer;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [JLAS-W0108] Pure text helpers for the JLPT path: kana normalization, romaji input, retype grading, kanji reading
 * labels, learned-kana coverage and Vietnamese meaning keys. No state, no I/O.
 */
public final class JpathKanaText {

    private static final Map<String, String> ROMAJI = new HashMap<>();
    private static final int MAX_ROMAJI_KEY = 3;
    private static final String VOWELS = "aeiou";
    private static final String ROW_VOWELS = "aiueo";
    private static final String[] KANA_BY_VOWEL = {
            "あかがさざただなはばぱまやらわぁゃ", "いきぎしじちぢにひびぴみりぃ",
            "うくぐすずつづぬふぶぷむゆるぅゅゔ", "えけげせぜてでねへべぺめれぇ", "おこごそぞとどのほぼぽもよろをぉょ"};
    private static final String COVERAGE_IGNORED = "、。・！？";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Map<Character, Character> SMALL_BY_LARGE = Map.of(
            'あ', 'ぁ', 'い', 'ぃ', 'う', 'ぅ', 'え', 'ぇ', 'お', 'ぉ',
            'ア', 'ァ', 'イ', 'ィ', 'ウ', 'ゥ', 'エ', 'ェ', 'オ', 'ォ');

    static {
        String[][] rows = {
                {"a", "あ"}, {"i", "い"}, {"u", "う"}, {"e", "え"}, {"o", "お"},
                {"ka", "か"}, {"ki", "き"}, {"ku", "く"}, {"ke", "け"}, {"ko", "こ"},
                {"kya", "きゃ"}, {"kyu", "きゅ"}, {"kyo", "きょ"},
                {"ga", "が"}, {"gi", "ぎ"}, {"gu", "ぐ"}, {"ge", "げ"}, {"go", "ご"},
                {"gya", "ぎゃ"}, {"gyu", "ぎゅ"}, {"gyo", "ぎょ"},
                {"sa", "さ"}, {"si", "し"}, {"shi", "し"}, {"su", "す"}, {"se", "せ"}, {"so", "そ"},
                {"sha", "しゃ"}, {"shu", "しゅ"}, {"sho", "しょ"}, {"she", "しぇ"},
                {"sya", "しゃ"}, {"syu", "しゅ"}, {"syo", "しょ"},
                {"za", "ざ"}, {"zi", "じ"}, {"ji", "じ"}, {"zu", "ず"}, {"ze", "ぜ"}, {"zo", "ぞ"},
                {"ja", "じゃ"}, {"ju", "じゅ"}, {"jo", "じょ"}, {"je", "じぇ"},
                {"jya", "じゃ"}, {"jyu", "じゅ"}, {"jyo", "じょ"},
                {"zya", "じゃ"}, {"zyu", "じゅ"}, {"zyo", "じょ"},
                {"ta", "た"}, {"ti", "ち"}, {"chi", "ち"}, {"tu", "つ"}, {"tsu", "つ"}, {"te", "て"}, {"to", "と"},
                {"cha", "ちゃ"}, {"chu", "ちゅ"}, {"cho", "ちょ"}, {"che", "ちぇ"},
                {"tya", "ちゃ"}, {"tyu", "ちゅ"}, {"tyo", "ちょ"},
                {"da", "だ"}, {"di", "ぢ"}, {"du", "づ"}, {"de", "で"}, {"do", "ど"},
                {"dya", "ぢゃ"}, {"dyu", "ぢゅ"}, {"dyo", "ぢょ"},
                {"na", "な"}, {"ni", "に"}, {"nu", "ぬ"}, {"ne", "ね"}, {"no", "の"},
                {"nya", "にゃ"}, {"nyu", "にゅ"}, {"nyo", "にょ"},
                {"ha", "は"}, {"hi", "ひ"}, {"hu", "ふ"}, {"fu", "ふ"}, {"he", "へ"}, {"ho", "ほ"},
                {"hya", "ひゃ"}, {"hyu", "ひゅ"}, {"hyo", "ひょ"},
                {"fa", "ふぁ"}, {"fi", "ふぃ"}, {"fe", "ふぇ"}, {"fo", "ふぉ"},
                {"ba", "ば"}, {"bi", "び"}, {"bu", "ぶ"}, {"be", "べ"}, {"bo", "ぼ"},
                {"bya", "びゃ"}, {"byu", "びゅ"}, {"byo", "びょ"},
                {"pa", "ぱ"}, {"pi", "ぴ"}, {"pu", "ぷ"}, {"pe", "ぺ"}, {"po", "ぽ"},
                {"pya", "ぴゃ"}, {"pyu", "ぴゅ"}, {"pyo", "ぴょ"},
                {"ma", "ま"}, {"mi", "み"}, {"mu", "む"}, {"me", "め"}, {"mo", "も"},
                {"mya", "みゃ"}, {"myu", "みゅ"}, {"myo", "みょ"},
                {"ya", "や"}, {"yu", "ゆ"}, {"yo", "よ"},
                {"ra", "ら"}, {"ri", "り"}, {"ru", "る"}, {"re", "れ"}, {"ro", "ろ"},
                {"rya", "りゃ"}, {"ryu", "りゅ"}, {"ryo", "りょ"},
                {"wa", "わ"}, {"wo", "を"}, {"vu", "ゔ"},
                {"xa", "ぁ"}, {"xi", "ぃ"}, {"xu", "ぅ"}, {"xe", "ぇ"}, {"xo", "ぉ"},
                {"la", "ぁ"}, {"li", "ぃ"}, {"lu", "ぅ"}, {"le", "ぇ"}, {"lo", "ぉ"},
                {"xya", "ゃ"}, {"xyu", "ゅ"}, {"xyo", "ょ"}, {"lya", "ゃ"}, {"lyu", "ゅ"}, {"lyo", "ょ"},
                {"xtu", "っ"}, {"ltu", "っ"},
        };
        for (String[] row : rows) {
            ROMAJI.put(row[0], row[1]);
        }
    }

    private JpathKanaText() {
    }

    /** katakana → hiragana(NFKC(text)); keeps ー and ・; null → "". */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            out.append(c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c);
        }
        return out.toString();
    }

    /**
     * Hepburn and basic kunrei romaji → hiragana. {@code n'}, {@code n} before a consonant or at the end, and
     * {@code nn} before a consonant or at the end give ん ({@code nn} before a vowel or y is ん + n-syllable, as
     * Hepburn writes おんな "onna"); a doubled consonant gives っ; {@code -} gives ー; other characters stay as-is.
     */
    public static String romajiToHiragana(String romaji) {
        if (romaji == null) {
            return "";
        }
        String s = romaji.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
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
                    char afterNn = i + 2 < s.length() ? s.charAt(i + 2) : '\0';
                    out.append('ん');
                    i += isVowelOrY(afterNn) ? 1 : 2;
                    continue;
                }
                if (!isVowelOrY(next)) {
                    out.append('ん');
                    i++;
                    continue;
                }
            }
            if (isConsonant(c) && (next == c || (c == 't' && next == 'c'))) {
                out.append('っ');
                i++;
                continue;
            }
            String kana = null;
            int length = Math.min(MAX_ROMAJI_KEY, s.length() - i);
            for (; length > 0; length--) {
                kana = ROMAJI.get(s.substring(i, i + length));
                if (kana != null) {
                    break;
                }
            }
            if (kana != null) {
                out.append(kana);
                i += length;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * EXACT when both normalized readings are equal, MINOR when only {@link #minorKey} matches, else WRONG. Typed
     * text containing ASCII letters is read as romaji first. Blank input is WRONG.
     */
    public static JpathRetypeVerdict retypeVerdict(String expectedReading, String typed) {
        String input = typed == null ? "" : typed.trim();
        if (input.isEmpty()) {
            return JpathRetypeVerdict.WRONG;
        }
        if (containsAsciiLetter(input)) {
            input = romajiToHiragana(input);
        }
        String expected = normalize(expectedReading == null ? "" : expectedReading.trim());
        String actual = normalize(input);
        if (expected.isEmpty()) {
            return JpathRetypeVerdict.WRONG;
        }
        if (expected.equals(actual)) {
            return JpathRetypeVerdict.EXACT;
        }
        return minorKey(expected).equals(minorKey(actual)) ? JpathRetypeVerdict.MINOR : JpathRetypeVerdict.WRONG;
    }

    /** Loose key: drops っ ん ー, a vowel lengthening the previous kana, and reads a final は as わ. */
    static String minorKey(String text) {
        String hira = normalize(text);
        StringBuilder out = new StringBuilder(hira.length());
        for (int i = 0; i < hira.length(); i++) {
            char c = hira.charAt(i);
            if (c == 'っ' || c == 'ん' || c == 'ー') {
                continue;
            }
            if (out.length() > 0 && lengthens(vowelOf(out.charAt(out.length() - 1)), c)) {
                continue;
            }
            out.append(c);
        }
        int last = out.length() - 1;
        if (last >= 0 && out.charAt(last) == 'は') {
            out.setCharAt(last, 'わ');
        }
        return out.toString();
    }

    /** {@code ひと.つ} → {@code ひと(つ)}; leading/trailing {@code -} dropped; null → "". */
    public static String kanjiReadingLabel(String reading) {
        if (reading == null) {
            return "";
        }
        String r = reading.trim();
        while (r.startsWith("-")) {
            r = r.substring(1);
        }
        while (r.endsWith("-")) {
            r = r.substring(0, r.length() - 1);
        }
        int dot = r.indexOf('.');
        return dot < 0 ? r : r.substring(0, dot) + "(" + r.substring(dot + 1) + ")";
    }

    /**
     * Learned kana plus what they unlock: っ with つ, ッ with ツ, small ぁぃぅぇぉ / ァィゥェォ with their large
     * kana, ー once any katakana is learned. Multi-character items also add each of their characters.
     */
    public static Set<String> expandLearned(Collection<String> learned) {
        Set<String> out = new LinkedHashSet<>();
        if (learned == null) {
            return out;
        }
        boolean anyKatakana = false;
        for (String item : learned) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            out.add(item);
            for (int i = 0; i < item.length(); i++) {
                char c = item.charAt(i);
                out.add(String.valueOf(c));
                if (c == 'つ') {
                    out.add("っ");
                } else if (c == 'ツ') {
                    out.add("ッ");
                }
                Character small = SMALL_BY_LARGE.get(c);
                if (small != null) {
                    out.add(String.valueOf(small));
                }
                if (c >= 'ァ' && c <= 'ヺ') {
                    anyKatakana = true;
                }
            }
        }
        if (anyKatakana) {
            out.add("ー");
        }
        return out;
    }

    /** True when every character of {@code text} (ignoring whitespace and 、。・！？) is in {@code learned}. */
    public static boolean coveredBy(String text, Set<String> learned) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || cp == '　' || COVERAGE_IGNORED.indexOf(cp) >= 0) {
                continue;
            }
            if (learned == null || !learned.contains(new String(Character.toChars(cp)))) {
                return false;
            }
        }
        return true;
    }

    /** True when the text has a CJK ideograph (U+4E00–9FFF, U+3400–4DBF) or 々. */
    public static boolean containsCjk(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints().anyMatch(cp -> (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF) || cp == 0x3005);
    }

    /** Lowercase, strip diacritics (NFD minus marks), đ → d, collapse whitespace; null → "". */
    public static String foldVi(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        String stripped = MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
        return SPACES.matcher(stripped).replaceAll(" ").trim();
    }

    /** Text before the first {@code ;}, trimmed; null → "". */
    public static String firstSense(String meaning) {
        if (meaning == null) {
            return "";
        }
        int semi = meaning.indexOf(';');
        return (semi < 0 ? meaning : meaning.substring(0, semi)).trim();
    }

    /** {@code foldVi(firstSense(meaning))}. */
    public static String meaningKey(String meaning) {
        return foldVi(firstSense(meaning));
    }

    private static boolean isVowelOrY(char c) {
        return VOWELS.indexOf(c) >= 0 || c == 'y';
    }

    private static boolean isConsonant(char c) {
        return c >= 'a' && c <= 'z' && VOWELS.indexOf(c) < 0 && c != 'n';
    }

    private static boolean containsAsciiLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    /** Same-row lengthening: …a+あ, …i+い, …u+う, …e+え/い, …o+お/う. */
    private static boolean lengthens(char vowel, char c) {
        return switch (vowel) {
            case 'a' -> c == 'あ';
            case 'i' -> c == 'い';
            case 'u' -> c == 'う';
            case 'e' -> c == 'え' || c == 'い';
            case 'o' -> c == 'お' || c == 'う';
            default -> false;
        };
    }

    /** Vowel ending a hiragana (small ゃゅょ count as a/u/o); a space when none. */
    private static char vowelOf(char kana) {
        for (int r = 0; r < KANA_BY_VOWEL.length; r++) {
            if (KANA_BY_VOWEL[r].indexOf(kana) >= 0) {
                return ROW_VOWELS.charAt(r);
            }
        }
        return ' ';
    }
}
