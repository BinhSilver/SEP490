package com.jlas.kaiwa.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đổi chữ romaji người học gõ (ví dụ {@code nitotte}) sang hiragana để tìm nguồn và để model biết người học hỏi mẫu nào.
 * Chỉ dùng khi câu hỏi không có chữ Nhật nào; tiếng Việt không dấu cũng là chữ Latin nên chỉ nhận từ đổi trọn vẹn được.
 */
public final class RomajiKana {

    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    private static final Pattern WORD = Pattern.compile("[A-Za-z']{4,}");
    /** Từ romaji có ít nhất hai âm tiết (nguyên âm, phụ âm, nguyên âm). Một âm tiết tiếng Việt không dấu như "giai", "muon" không có dạng này. */
    private static final Pattern TWO_SYLLABLES = Pattern.compile("[aeiou][^aeiou']+[aeiou]", Pattern.CASE_INSENSITIVE);
    private static final int MAX_HINTS = 3;

    private static final Map<String, String> SYLLABLES = Map.ofEntries(
            Map.entry("a", "あ"), Map.entry("i", "い"), Map.entry("u", "う"), Map.entry("e", "え"), Map.entry("o", "お"),
            Map.entry("ka", "か"), Map.entry("ki", "き"), Map.entry("ku", "く"), Map.entry("ke", "け"), Map.entry("ko", "こ"),
            Map.entry("ga", "が"), Map.entry("gi", "ぎ"), Map.entry("gu", "ぐ"), Map.entry("ge", "げ"), Map.entry("go", "ご"),
            Map.entry("sa", "さ"), Map.entry("shi", "し"), Map.entry("si", "し"), Map.entry("su", "す"), Map.entry("se", "せ"), Map.entry("so", "そ"),
            Map.entry("za", "ざ"), Map.entry("ji", "じ"), Map.entry("zi", "じ"), Map.entry("zu", "ず"), Map.entry("ze", "ぜ"), Map.entry("zo", "ぞ"),
            Map.entry("ta", "た"), Map.entry("chi", "ち"), Map.entry("ti", "ち"), Map.entry("tsu", "つ"), Map.entry("tu", "つ"),
            Map.entry("te", "て"), Map.entry("to", "と"),
            Map.entry("da", "だ"), Map.entry("de", "で"), Map.entry("do", "ど"),
            Map.entry("na", "な"), Map.entry("ni", "に"), Map.entry("nu", "ぬ"), Map.entry("ne", "ね"), Map.entry("no", "の"),
            Map.entry("ha", "は"), Map.entry("hi", "ひ"), Map.entry("fu", "ふ"), Map.entry("hu", "ふ"), Map.entry("he", "へ"), Map.entry("ho", "ほ"),
            Map.entry("ba", "ば"), Map.entry("bi", "び"), Map.entry("bu", "ぶ"), Map.entry("be", "べ"), Map.entry("bo", "ぼ"),
            Map.entry("pa", "ぱ"), Map.entry("pi", "ぴ"), Map.entry("pu", "ぷ"), Map.entry("pe", "ぺ"), Map.entry("po", "ぽ"),
            Map.entry("ma", "ま"), Map.entry("mi", "み"), Map.entry("mu", "む"), Map.entry("me", "め"), Map.entry("mo", "も"),
            Map.entry("ya", "や"), Map.entry("yu", "ゆ"), Map.entry("yo", "よ"),
            Map.entry("ra", "ら"), Map.entry("ri", "り"), Map.entry("ru", "る"), Map.entry("re", "れ"), Map.entry("ro", "ろ"),
            Map.entry("wa", "わ"), Map.entry("wo", "を"),
            Map.entry("kya", "きゃ"), Map.entry("kyu", "きゅ"), Map.entry("kyo", "きょ"),
            Map.entry("gya", "ぎゃ"), Map.entry("gyu", "ぎゅ"), Map.entry("gyo", "ぎょ"),
            Map.entry("sha", "しゃ"), Map.entry("shu", "しゅ"), Map.entry("sho", "しょ"),
            Map.entry("ja", "じゃ"), Map.entry("ju", "じゅ"), Map.entry("jo", "じょ"),
            Map.entry("cha", "ちゃ"), Map.entry("chu", "ちゅ"), Map.entry("cho", "ちょ"),
            Map.entry("nya", "にゃ"), Map.entry("nyu", "にゅ"), Map.entry("nyo", "にょ"),
            Map.entry("hya", "ひゃ"), Map.entry("hyu", "ひゅ"), Map.entry("hyo", "ひょ"),
            Map.entry("bya", "びゃ"), Map.entry("byu", "びゅ"), Map.entry("byo", "びょ"),
            Map.entry("pya", "ぴゃ"), Map.entry("pyu", "ぴゅ"), Map.entry("pyo", "ぴょ"),
            Map.entry("mya", "みゃ"), Map.entry("myu", "みゅ"), Map.entry("myo", "みょ"),
            Map.entry("rya", "りゃ"), Map.entry("ryu", "りゅ"), Map.entry("ryo", "りょ"));

    private RomajiKana() { }

    /** Hiragana của một từ romaji, hoặc {@code null} khi từ đó không phải romaji trọn vẹn. */
    public static String toHiragana(String word) {
        String s = word.toLowerCase(Locale.ROOT).replace("'", "");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            boolean hasNext = i + 1 < s.length();
            if (c == 'n' && (!hasNext || "aiueoy".indexOf(s.charAt(i + 1)) < 0)) {
                out.append('ん');
                i += hasNext && s.charAt(i + 1) == 'n' ? 2 : 1;
                continue;
            }
            if (hasNext && c == s.charAt(i + 1) && "aiueon".indexOf(c) < 0) {
                out.append('っ');
                i++;
                continue;
            }
            String kana = null;
            for (int len = 3; len >= 1 && kana == null; len--) {
                if (i + len <= s.length()) {
                    kana = SYLLABLES.get(s.substring(i, i + len));
                    if (kana != null) i += len;
                }
            }
            if (kana == null) return null;
            out.append(kana);
        }
        String result = out.toString();
        return result.isEmpty() || result.startsWith("ん") || result.startsWith("っ") ? null : result;
    }

    /** Các từ romaji trong câu hỏi đổi được sang hiragana; rỗng khi câu hỏi đã có chữ Nhật. */
    public static List<Hint> hints(String question) {
        if (question == null || JAPANESE.matcher(question).find()) return List.of();
        Set<Hint> hints = new LinkedHashSet<>();
        Matcher words = WORD.matcher(question);
        while (words.find() && hints.size() < MAX_HINTS) {
            if (!TWO_SYLLABLES.matcher(words.group()).find()) continue;
            String kana = toHiragana(words.group());
            if (kana != null) hints.add(new Hint(words.group(), kana));
        }
        return new ArrayList<>(hints);
    }

    public record Hint(String romaji, String kana) { }
}
