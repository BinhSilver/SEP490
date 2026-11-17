package com.jlas.learning.service.impl.text;

import com.atilika.kuromoji.ipadic.Token;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F153: derives combined hiragana readings for Kanji numerals and their following counter (e.g. {@code 二十分} →
 * {@code にじゅっぷん}, {@code 三階} → {@code さんがい}, {@code 二冊} → {@code にさつ}), returning {@code null} when a span is
 * ambiguous or unknown so callers leave it plain rather than guessing.
 */
final class KanjiNumberReadings {

    private static final String KANJI_NUMERALS = "一二三四五六七八九十百千万億兆〇零";
    private static final String KNOWN_NOUN_COUNTERS = "月日人年時回階番冊本枚台杯匹歳分秒円週課行泊足着度つ";

    private static final String[] ONES = {
            "", "いち", "に", "さん", "よん", "ご", "ろく", "なな", "はち", "きゅう"
    };
    private static final String[] TENS = {
            "", "じゅう", "にじゅう", "さんじゅう", "よんじゅう", "ごじゅう", "ろくじゅう", "ななじゅう", "はちじゅう", "きゅうじゅう"
    };
    private static final String[] HUNDREDS = {
            "", "ひゃく", "にひゃく", "さんびゃく", "よんひゃく", "ごひゃく", "ろっぴゃく", "ななひゃく", "はっぴゃく", "きゅうひゃく"
    };
    private static final String[] THOUSANDS = {
            "", "せん", "にせん", "さんぜん", "よんせん", "ごせん", "ろくせん", "ななせん", "はっせん", "きゅうせん"
    };
    private static final String[] TSU_READINGS = {
            "", "ひとつ", "ふたつ", "みっつ", "よっつ", "いつつ", "むっつ", "ななつ", "やっつ", "ここのつ"
    };

    private static final Map<Long, String> SPECIAL_DAYS = Map.ofEntries(
            Map.entry(2L, "ふつか"),
            Map.entry(3L, "みっか"),
            Map.entry(4L, "よっか"),
            Map.entry(5L, "いつか"),
            Map.entry(6L, "むいか"),
            Map.entry(7L, "なのか"),
            Map.entry(8L, "ようか"),
            Map.entry(9L, "ここのか"),
            Map.entry(10L, "とおか"),
            Map.entry(14L, "じゅうよっか"),
            Map.entry(20L, "はつか"),
            Map.entry(24L, "にじゅうよっか")
    );

    private static final Set<String> K_COUNTERS = Set.of(
            "回", "個", "課", "巻", "か月", "ヶ月", "ヵ月", "箇月", "カ月",
            "件", "級", "曲", "回戦", "か所", "箇所", "ヶ所", "ヵ所", "か月間", "ヶ月間", "ヵ月間"
    );
    private static final Set<String> S_COUNTERS = Set.of(
            "冊", "週間", "週", "勝", "社", "周年", "歳児", "戦"
    );
    private static final Set<String> T_COUNTERS = Set.of(
            "着", "丁目", "点", "通"
    );
    private static final Map<String, String[]> H_PB_COUNTERS = Map.of(
            "本", new String[] { "ぽん", "ぼん" },
            "杯", new String[] { "ぱい", "ばい" },
            "匹", new String[] { "ぴき", "びき" }
    );
    private static final Map<String, String> H_P_COUNTERS = Map.of(
            "泊", "ぱく",
            "発", "ぱつ"
    );
    // Counters taking kun'yomi (ひと/ふた…) or multiple readings after a numeral; leave plain instead of emitting wrong on'yomi.
    private static final Set<String> AMBIGUOUS_COUNTERS = Set.of(
            "晩", "箱", "皿", "袋", "組", "口", "棟", "粒", "束", "切", "切れ", "株", "品", "部屋", "間", "目"
    );

    private KanjiNumberReadings() { }

    static boolean isKanjiNumeral(String surface) {
        if (surface == null || surface.isEmpty()) return false;
        for (int i = 0; i < surface.length(); i++) {
            if (KANJI_NUMERALS.indexOf(surface.charAt(i)) < 0) return false;
        }
        return true;
    }

    static boolean isKnownNounCounter(String surface) {
        return surface != null && surface.length() == 1 && KNOWN_NOUN_COUNTERS.indexOf(surface.charAt(0)) >= 0;
    }

    /** Combined hiragana reading for {@code numTokens} + optional {@code counterToken}, or {@code null} if unknown/ambiguous. */
    static String read(List<Token> numTokens, Token counterToken) {
        if (numTokens == null || numTokens.isEmpty()) return null;
        StringBuilder raw = new StringBuilder();
        for (Token token : numTokens) {
            raw.append(token.getSurface());
        }
        ParsedNumber parsed = parse(raw.toString());
        if (parsed == null) return null;
        if (counterToken == null) return parsed.reading;
        return readWithCounter(parsed, counterToken);
    }

    private static String readWithCounter(ParsedNumber n, Token counterToken) {
        String counter = counterToken.getSurface();
        String rawReading = counterToken.getReading();
        if (rawReading == null || rawReading.isBlank() || "*".equals(rawReading)) return null;
        if (AMBIGUOUS_COUNTERS.contains(counter)) return null;

        String cReading = GeneratedFurigana.toHiragana(rawReading);
        long val = n.value;
        int lastDigit = n.lastDigit;

        switch (counter) {
            case "日" -> {
                if (val <= 1) return null; // 一日 is ambiguous between ついたち and いちにち.
                String special = SPECIAL_DAYS.get(val);
                return special != null ? special : n.reading + "にち";
            }
            case "日間" -> {
                if (val == 0) return null;
                if (val == 1) return "いちにちかん";
                String special = SPECIAL_DAYS.get(val);
                return (special != null ? special : n.reading + "にち") + "かん";
            }
            case "人" -> {
                if (val == 1) return "ひとり";
                if (val == 2) return "ふたり";
                if (val == 7) return "しちにん";
                if (lastDigit == 4) return n.prefix + "よにん";
                return n.reading + "にん";
            }
            case "つ" -> {
                return val >= 1 && val <= 9 ? TSU_READINGS[(int) val] : null;
            }
            case "歳", "才" -> {
                if (val == 20 && "歳".equals(counter)) return "はたち";
                String head = sokuonHead(n, false, false);
                return (head != null ? head : n.reading) + "さい";
            }
            case "時", "時半" -> {
                String suffix = "時".equals(counter) ? "じ" : "じはん";
                if (lastDigit == 4) return n.prefix + "よ" + suffix;
                if (lastDigit == 7) return n.prefix + "しち" + suffix;
                if (lastDigit == 9) return n.prefix + "く" + suffix;
                return n.reading + suffix;
            }
            case "時間" -> {
                if (lastDigit == 4) return n.prefix + "よじかん";
                if (lastDigit == 9) return n.prefix + "くじかん";
                return n.reading + "じかん";
            }
            case "年", "年間" -> {
                String suffix = "年".equals(counter) ? "ねん" : "ねんかん";
                return lastDigit == 4 ? n.prefix + "よ" + suffix : n.reading + suffix;
            }
            case "月" -> {
                if (lastDigit == 4) return n.prefix + "しがつ";
                if (lastDigit == 7) return n.prefix + "しちがつ";
                if (lastDigit == 9) return n.prefix + "くがつ";
                return n.reading + "がつ";
            }
            case "円" -> {
                return lastDigit == 4 ? n.prefix + "よえん" : n.reading + "えん";
            }
            case "階", "軒" -> {
                String voiced = "階".equals(counter) ? "がい" : "げん";
                if (lastDigit == 3) return n.prefix + "さん" + voiced;
                if (n.endsInN()) return n.reading + voiced;
                String head = sokuonHead(n, true, true);
                return (head != null ? head : n.reading) + cReading;
            }
            case "足" -> {
                if (lastDigit == 3) return n.prefix + "さんぞく";
                if (n.endsInN()) return n.reading + "ぞく";
                String head = sokuonHead(n, false, false);
                return (head != null ? head : n.reading) + "そく";
            }
            case "分", "分間" -> {
                String suffix = "分".equals(counter) ? "" : "かん";
                if (val == 0) return "れいふん" + suffix;
                if (lastDigit == 3) return n.prefix + "さんぷん" + suffix;
                if (lastDigit == 4) return n.prefix + "よんぷん" + suffix;
                if (n.endsInN()) return n.reading + "ぷん" + suffix;
                String head = sokuonHead(n, true, true);
                if (head != null) return head + "ぷん" + suffix;
                return n.reading + "ふん" + suffix;
            }
            default -> {
                if (K_COUNTERS.contains(counter)) {
                    String head = sokuonHead(n, true, true);
                    return (head != null ? head : n.reading) + cReading;
                }
                if (S_COUNTERS.contains(counter) || T_COUNTERS.contains(counter)) {
                    String head = sokuonHead(n, false, false);
                    return (head != null ? head : n.reading) + cReading;
                }
                String[] pb = H_PB_COUNTERS.get(counter);
                if (pb != null) {
                    if (lastDigit == 3) return n.prefix + "さん" + pb[1];
                    if (n.endsInN()) return n.reading + pb[1];
                    String head = sokuonHead(n, true, true);
                    return head != null ? head + pb[0] : n.reading + cReading;
                }
                String pStem = H_P_COUNTERS.get(counter);
                if (pStem != null) {
                    if (lastDigit == 3) return n.prefix + "さん" + pStem;
                    if (n.endsInN()) return n.reading + pStem;
                    String head = sokuonHead(n, true, true);
                    return head != null ? head + pStem : n.reading + cReading;
                }
                return n.reading + cReading;
            }
        }
    }

    private static String sokuonHead(ParsedNumber n, boolean onSix, boolean onKuEnd) {
        if (n.lastDigit == 1) return n.prefix + "いっ";
        if (onSix && n.lastDigit == 6) return n.prefix + "ろっ";
        if (n.lastDigit == 8) return n.prefix + "はっ";
        if (n.endsInTen()) return n.reading.substring(0, n.reading.length() - 3) + "じゅっ";
        if (onKuEnd && n.endsInKu()) return n.reading.substring(0, n.reading.length() - 1) + "っ";
        return null;
    }

    private static ParsedNumber parse(String s) {
        if (s.indexOf('〇') >= 0) return null;
        if ("零".equals(s)) return new ParsedNumber(0L, "れい", "", 0);
        if (s.indexOf('零') >= 0) return null;

        long total = 0L;
        int group = 0;
        int pendingDigit = 0;
        int lastSmallPlace = Integer.MAX_VALUE;
        long lastLargePlace = Long.MAX_VALUE;
        boolean explicitOneThousand = false;
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int digit = kanjiDigit(c);
            if (digit > 0) {
                if (pendingDigit != 0 || lastSmallPlace <= 1) return null;
                pendingDigit = digit;
                continue;
            }
            int smallPlace = switch (c) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                default -> 0;
            };
            if (smallPlace > 0) {
                if (smallPlace >= lastSmallPlace) return null;
                if (pendingDigit == 1 && (smallPlace == 10 || smallPlace == 100)) return null;
                if (smallPlace == 1000 && pendingDigit == 1) explicitOneThousand = true;
                int coeff = pendingDigit == 0 ? 1 : pendingDigit;
                group += coeff * smallPlace;
                pendingDigit = 0;
                lastSmallPlace = smallPlace;
                continue;
            }
            long largePlace = switch (c) {
                case '万' -> 10_000L;
                case '億' -> 100_000_000L;
                case '兆' -> 1_000_000_000_000L;
                default -> 0L;
            };
            if (largePlace == 0L || largePlace >= lastLargePlace) return null;
            group += pendingDigit;
            if (group == 0) return null;
            appendGroup(out, group, explicitOneThousand, String.valueOf(c));
            total += group * largePlace;
            group = 0;
            pendingDigit = 0;
            lastSmallPlace = Integer.MAX_VALUE;
            lastLargePlace = largePlace;
            explicitOneThousand = false;
        }

        group += pendingDigit;
        total += group;
        if (total <= 0) return null;
        if (group > 0) appendGroup(out, group, explicitOneThousand, null);

        String reading = out.toString();
        int lastDigit = (int) (total % 10);
        String prefix = lastDigit == 0
                ? reading
                : reading.substring(0, reading.length() - ONES[lastDigit].length());
        return new ParsedNumber(total, reading, prefix, lastDigit);
    }

    private static void appendGroup(StringBuilder out, int group, boolean explicitOneThousand, String largeKanji) {
        StringBuilder g = new StringBuilder();
        int thousands = group / 1000;
        int hundreds = (group % 1000) / 100;
        int tens = (group % 100) / 10;
        int ones = group % 10;
        if (thousands > 0) g.append(thousands == 1 && explicitOneThousand ? "いっせん" : THOUSANDS[thousands]);
        if (hundreds > 0) g.append(HUNDREDS[hundreds]);
        if (tens > 0) g.append(TENS[tens]);
        if (ones > 0) g.append(ONES[ones]);

        if (largeKanji == null) {
            out.append(g);
            return;
        }
        switch (largeKanji) {
            case "万" -> out.append(g).append("まん");
            case "億" -> out.append(g).append("おく");
            case "兆" -> {
                if (group % 10 == 1) out.append(g, 0, g.length() - ONES[1].length()).append("いっちょう");
                else if (group % 10 == 8) out.append(g, 0, g.length() - ONES[8].length()).append("はっちょう");
                else if (group % 10 == 0 && group % 100 != 0) {
                    out.append(g, 0, g.length() - 3).append("じゅっちょう");
                } else {
                    out.append(g).append("ちょう");
                }
            }
            default -> throw new IllegalStateException("Unexpected unit: " + largeKanji);
        }
    }

    private static int kanjiDigit(char c) {
        return switch (c) {
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> 0;
        };
    }

    private record ParsedNumber(long value, String reading, String prefix, int lastDigit) {
        boolean endsInTen() {
            return value > 0 && lastDigit == 0 && value % 100 != 0;
        }

        boolean endsInKu() {
            return value > 0 && value % 100 == 0 && value % 1000 != 0;
        }

        boolean endsInN() {
            return value > 0 && ((value % 1000 == 0 && value % 10000 != 0)
                    || (value % 10000 == 0 && (value / 10000) % 10000 != 0));
        }
    }
}
