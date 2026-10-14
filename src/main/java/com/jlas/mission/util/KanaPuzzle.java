package com.jlas.mission.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Shared kana tokenizer and orthogonal-adjacency rule for the multi-word puzzle. */
public final class KanaPuzzle {
    private KanaPuzzle() {}

    public record Cell(int id, int row, int col, String kana) {}

    /** Keep natural hiragana/katakana spelling, combining marks, sokuon and long vowels. */
    public static List<String> tokenize(String reading) {
        if (reading == null) return List.of();
        String text = Normalizer.normalize(reading.strip(), Normalizer.Form.NFKC);
        List<String> tokens = new ArrayList<>();
        String small = "ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ";
        for (int cp : text.codePoints().toArray()) {
            if (!((cp >= 0x3041 && cp <= 0x3096) || (cp >= 0x30A1 && cp <= 0x30FA) || cp == 0x30FC)) {
                return List.of(); // Reject spaces, punctuation, kanji and malformed readings.
            }
            String kana = new String(Character.toChars(cp));
            if (small.contains(kana)) {
                if (tokens.isEmpty()) return List.of();
                int last = tokens.size() - 1;
                String previous = tokens.get(last);
                if (previous.length() != 1 || "っッんンー".contains(previous) || small.contains(previous)) {
                    return List.of();
                }
                tokens.set(last, previous + kana);
            } else {
                if (tokens.isEmpty() && "っッんンー".contains(kana)) return List.of();
                tokens.add(kana);
            }
        }
        return List.copyOf(tokens);
    }

    public static boolean adjacent(Cell a, Cell b) {
        return Math.abs(a.row() - b.row()) + Math.abs(a.col() - b.col()) == 1;
    }

}
