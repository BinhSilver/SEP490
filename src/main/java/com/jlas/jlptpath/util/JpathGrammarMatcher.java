package com.jlas.jlptpath.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [JLAS-W0108] Matches a text selection against grammar patterns (Look up, plan Task 62). A pattern becomes one or more
 * lists of literal segments: {@code 漢字(かな)} gives a kanji and a kana version, brackets holding Latin text are dropped,
 * then the text is cut at Latin letters, digits, {@code + - _}, spaces, {@code 〜 ～ ~} and {@code ・ / ／ ( ) （ ） 「 」 [ ]}
 * and every segment is normalized on its own (cutting first keeps the {@code 〜} cut points). Voiced kana are never
 * folded ({@code だ} ≠ {@code た}). No state, no I/O.
 */
public final class JpathGrammarMatcher {

    /** Below this score a pattern does not match. */
    public static final int MIN_SCORE = 2;

    private static final Pattern KANJI_READING = Pattern.compile("([\\p{IsHan}々]+)[(（]([\\p{IsHiragana}ー]+)[)）]");
    private static final Pattern LATIN_BRACKETS = Pattern.compile("[(（][^()（）]*[A-Za-z][^()（）]*[)）]");
    private static final Pattern CUTS = Pattern.compile("[A-Za-z0-9+\\-_ 〜～~・/／()（）「」\\[\\]]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private JpathGrammarMatcher() {
    }

    /** NFKC without any whitespace; null → empty. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return SPACES.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC)).replaceAll("");
    }

    /** The segment lists of {@code pattern}: the kanji version first, then the kana version when it differs. */
    public static List<List<String>> alternatives(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return List.of();
        }
        Set<List<String>> out = new LinkedHashSet<>();
        for (String version : List.of(reading(pattern, false), reading(pattern, true))) {
            List<String> segments = segments(version);
            if (!segments.isEmpty()) {
                out.add(segments);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Sum of the segment lengths when every segment of one version of {@code pattern} occurs in the selection in
     * order (each searched from the end of the one before), the best version wins; below {@link #MIN_SCORE} → 0.
     */
    public static int score(String selection, String pattern) {
        String text = normalize(selection);
        int best = 0;
        for (List<String> segments : alternatives(pattern)) {
            best = Math.max(best, inOrder(text, segments));
        }
        return best < MIN_SCORE ? 0 : best;
    }

    /** Best score over the pattern and its variants. */
    public static int score(String selection, String pattern, List<String> variants) {
        int best = score(selection, pattern);
        if (variants != null) {
            for (String v : variants) {
                best = Math.max(best, score(selection, v));
            }
        }
        return best;
    }

    private static int inOrder(String text, List<String> segments) {
        int from = 0;
        int sum = 0;
        for (String seg : segments) {
            int at = text.indexOf(seg, from);
            if (at < 0) {
                return 0;
            }
            from = at + seg.length();
            sum += seg.length();
        }
        return sum;
    }

    /** {@code 漢字(かな)} → the kanji ({@code kana} false) or the reading ({@code kana} true). */
    private static String reading(String pattern, boolean kana) {
        Matcher m = KANJI_READING.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(kana ? m.group(2) : m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<String> segments(String version) {
        String text = LATIN_BRACKETS.matcher(version).replaceAll(" ");
        List<String> out = new ArrayList<>();
        for (String part : CUTS.split(text)) {
            String seg = normalize(part);
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
        return out;
    }
}
