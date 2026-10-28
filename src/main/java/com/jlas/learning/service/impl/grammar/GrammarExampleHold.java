package com.jlas.learning.service.impl.grammar;

import java.util.Set;

/** Canonical examples confirmed not to exercise their labelled pattern: Sekai N5 Bài 1, where the shared seed attaches
 * the lesson's sentences to the wrong points (audit 2026-10-22; the seed belongs to the content team). Learning withholds
 * them from the study view, the practice and the check explanation rather than teach a mismatched sentence. Matching is
 * on pattern + exact sentence, so a corrected seed is shown again without a code change. */
public final class GrammarExampleHold {
    private static final Set<String> HELD = Set.of(
            key("N1 は N2 じゃ ありません", "わたしの かいしゃは とうきょうです。"),
            key("N1 は N2 ですか", "ちちは かいしゃいんです。"),
            key("N1 も N2 です", "あのひとは せんせいじゃ ありません。"),
            key("N1 の N2", "おなまえは。"),
            key("Tên người + さん", "あねは ぎんこういんです。"),
            key("N は なんさい ですか", "あのひとは だれですか。"),
            key("はじめまして … どうぞ よろしく", "キムさんも かんこくじんです。"));

    private GrammarExampleHold() { }

    public static boolean held(String pattern, String exampleJapanese) {
        return pattern != null && exampleJapanese != null && HELD.contains(key(pattern, exampleJapanese));
    }

    private static String key(String pattern, String example) {
        return pattern.replaceAll("\\s+", "") + "|" + example.replaceAll("\\s+", "");
    }
}
