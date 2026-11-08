package com.jlas.jlptpath.service.impl;

import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] Everyday expressions that mean (almost) the same thing although they are written differently. A quiz
 * never offers two of one group as answer and distractor (owner 06/11: じゃ、また。 and さようなら both read as
 * "tạm biệt"). Matched on the reading (hiragana, as {@code normalized_reading}), the written form, or the Vietnamese
 * meaning core ({@code JpathCheckBuilder.Card.core}). Only short set phrases belong here, never ordinary words.
 */
final class JpathSenseGroups {

    private record Group(String id, List<String> readingPrefixes, List<String> viPhrases) {
    }

    private static final List<Group> GROUPS = List.of(
            new Group("FAREWELL", List.of("さようなら", "さよなら", "じゃあね", "じゃあまた", "じゃまた", "では、また", "ではまた",
                    "またね", "またあした", "またらいしゅう", "ばいばい", "しつれいします", "おさきにしつれいします"),
                    List.of("tạm biệt", "hẹn gặp lại", "chào tạm biệt", "xin phép về trước")),
            new Group("THANKS", List.of("ありがとう", "どうも", "おせわになりました", "おそれいります"),
                    List.of("cảm ơn", "cám ơn", "xin cảm ơn")),
            new Group("SORRY", List.of("すみません", "すいません", "ごめん", "もうしわけ", "しつれいしました"),
                    List.of("xin lỗi", "thành thật xin lỗi", "thứ lỗi")),
            new Group("NOT_AT_ALL", List.of("どういたしまして", "いいえ、どういたしまして", "とんでもない"),
                    List.of("không có gì", "không dám")));

    /** Readings are compared without spaces and the Japanese comma / full stop. */
    private static final Map<String, String> STRIP = Map.of("、", "", "。", "", " ", "", "　", "", "!", "", "！", "");

    private JpathSenseGroups() {
    }

    /** The group of a card, or null. {@code reading} / {@code expr} normalized, {@code viCore} = meaning core. */
    static String of(String reading, String expr, String viCore) {
        String r = strip(reading);
        String e = strip(expr);
        for (Group g : GROUPS) {
            for (String p : g.readingPrefixes()) {
                String q = strip(p);
                if ((!r.isEmpty() && r.startsWith(q)) || (!e.isEmpty() && e.startsWith(q))) {
                    return g.id();
                }
            }
        }
        if (viCore != null && !viCore.isBlank()) {
            for (Group g : GROUPS) {
                for (String v : g.viPhrases()) {
                    if (viCore.equals(v) || viCore.startsWith(v + " ") || viCore.startsWith(v + ";")
                            || viCore.startsWith(v + ",")) {
                        return g.id();
                    }
                }
            }
        }
        return null;
    }

    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        String t = s;
        for (Map.Entry<String, String> e : STRIP.entrySet()) {
            t = t.replace(e.getKey(), e.getValue());
        }
        return t;
    }
}
