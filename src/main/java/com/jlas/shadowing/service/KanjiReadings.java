package com.jlas.shadowing.service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-kanji readings (hiragana) used to split an approved word reading across its kanji, so a learner may
 * write part of the word in kana (祖ぼ, そ母). Loaded once from kanji_reading by {@link KanjiReadingsLoader};
 * while empty, answers are matched exactly as before (whole word in kanji or in kana).
 */
final class KanjiReadings {
    private static volatile Map<Integer, Set<String>> readings = Map.of();

    private KanjiReadings() {
    }

    static Set<String> of(int kanji) {
        return readings.getOrDefault(kanji, Set.of());
    }

    static void replace(Map<Integer, Set<String>> value) {
        Map<Integer, Set<String>> copy = new HashMap<>();
        value.forEach((kanji, set) -> copy.put(kanji, Set.copyOf(set)));
        readings = Map.copyOf(copy);
    }

    static int size() {
        return readings.size();
    }

    /** KANJIDIC style: ON in katakana, KUN with okurigana after "." and "-" for prefixes/suffixes. */
    static void collect(Map<Integer, Set<String>> into, String kanji, String raw) {
        if (kanji == null || raw == null || kanji.codePointCount(0, kanji.length()) != 1) return;
        String reading = toHiragana(raw.replace("-", "").trim());
        if (reading.isEmpty()) return;
        Set<String> set = into.computeIfAbsent(kanji.codePointAt(0), ignored -> new LinkedHashSet<>());
        int dot = reading.indexOf('.');
        if (dot > 0) set.add(reading.substring(0, dot));
        set.add(reading.replace(".", ""));
    }

    private static String toHiragana(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().map(cp -> cp >= 0x30A1 && cp <= 0x30F6 ? cp - 0x60 : cp).forEach(result::appendCodePoint);
        return result.toString();
    }
}
