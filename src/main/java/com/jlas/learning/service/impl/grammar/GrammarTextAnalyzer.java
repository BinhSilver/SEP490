package com.jlas.learning.service.impl.grammar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** [JLAS-W0193] Task 6 — deterministic, no-AI text analysis over canonical {@code grammar_point} text
 * (pattern/example). Every seed {@code pattern} and {@code example} sentence is already whitespace-segmented
 * into grammatical units (e.g. {@code "N1 は N2 です"}, {@code "わたしは グエンです。"}), so a plain
 * whitespace split is real segmentation of real approved content, not fabrication. Classification uses a
 * small fixed dictionary of Japanese particles/copulas reviewed once, applied the same way to every point —
 * never per-item AI output. */
public final class GrammarTextAnalyzer {
    private GrammarTextAnalyzer() { }

    public enum Role { PARTICLE, AUXILIARY, SLOT, OTHER }

    public record Token(String text, Role role) { }

    /** Ordered so longer/compound forms are tried before their shorter substrings; irrelevant to the
     * resulting {@link Role} bucket (both a form and its prefix map to AUXILIARY) but keeps the list
     * self-documenting. */
    private static final List<String> AUXILIARIES = List.of(
            "ではありません", "じゃありません", "でしょう", "ありません", "でした", "ですか", "です", "だ", "ます", "じゃ", "では");
    private static final List<String> PARTICLES = List.of(
            "から", "まで", "より", "へ", "で", "と", "も", "の", "が", "を", "に", "は", "や", "ね", "よ", "か");
    private static final String PUNCTUATION = "[、。！？　]";

    public static List<String> splitWords(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.trim().split("\\s+")).filter(s -> !s.isEmpty()).toList();
    }

    /** Component breakdown for a {@code pattern} formula (e.g. {@code "A は B です"}): one {@link Token}
     * per whitespace-delimited unit, tagged by the fixed dictionary above. */
    public static List<Token> classifyPattern(String pattern) {
        return splitWords(pattern).stream().map(w -> new Token(w, classify(w))).toList();
    }

    static Role classify(String word) {
        String stripped = strip(word);
        if (stripped.isEmpty()) return Role.OTHER;
        for (String aux : AUXILIARIES) if (stripped.contains(aux)) return Role.AUXILIARY;
        if (stripped.matches("N\\d*")) return Role.SLOT;
        for (String p : PARTICLES) if (stripped.equals(p) || stripped.contains(p)) return Role.PARTICLE;
        return Role.OTHER;
    }

    static String strip(String word) {
        return word == null ? "" : word.replaceAll(PUNCTUATION, "");
    }

    /** Whether a word from an example sentence carries a recognizable grammar marker, and is therefore a
     * reasonable fill-in-the-blank target. */
    static boolean isBlankable(String word) {
        Role role = classify(word);
        return role == Role.AUXILIARY || role == Role.PARTICLE;
    }

    /** Index (0-based) of the word chosen as the fill-blank target out of an example sentence's word list.
     * Prefers the first blankable word at index &ge; 1 (keeps the sentence-initial topic visible as
     * context); falls back to index 0 if that is the only blankable word; falls back to the last word when
     * nothing in the sentence matches any known marker at all — still real, deterministic, never invented. */
    public static int chooseBlankIndex(List<String> words) {
        if (words.isEmpty()) return -1;
        for (int i = 1; i < words.size(); i++) if (isBlankable(words.get(i))) return i;
        if (isBlankable(words.get(0))) return 0;
        return words.size() - 1;
    }

    /** Deterministic (seeded) shuffle of word indices for the ordering practice — stable across reloads
     * for the same grammar point, but not the identity order when there is more than one word. */
    public static List<Integer> shuffledOrder(long seed, int wordCount) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < wordCount; i++) order.add(i);
        if (wordCount < 2) return order;
        Collections.shuffle(order, new Random(seed));
        if (order.equals(sortedIdentity(wordCount))) Collections.swap(order, 0, 1);
        return order;
    }

    private static List<Integer> sortedIdentity(int wordCount) {
        List<Integer> identity = new ArrayList<>();
        for (int i = 0; i < wordCount; i++) identity.add(i);
        return identity;
    }

    /** Normalizes a fill-blank submission/answer for comparison: trims, collapses internal whitespace,
     * strips trailing sentence punctuation and case-folds (harmless for Japanese, matters if the point ever
     * mixes in latin text). */
    public static String normalizeAnswer(String s) {
        if (s == null) return "";
        String trimmed = s.trim().replaceAll("\\s+", " ");
        String noTrailingPunctuation = trimmed.replaceAll(PUNCTUATION + "+$", "");
        return noTrailingPunctuation.toLowerCase(Locale.ROOT);
    }
}
