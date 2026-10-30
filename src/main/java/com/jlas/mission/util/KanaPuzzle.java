package com.jlas.mission.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Shared kana tokenizer and orthogonal-adjacency rule for the multi-word puzzle. */
public final class KanaPuzzle {
    private KanaPuzzle() {}

    public record Cell(int id, int row, int col, String kana) {}

    // A sentence ending in a polite or copula form (お名前は何ですか, かしこまりました). Something must come first,
    // so 真下 (ました) stays; a bare ます is left to the textbook rule because 励ます is a word.
    private static final Pattern POLITE_FORM = Pattern.compile(
            ".(?:ま(?:した|せん|しょう)|で(?:す|した|しょう)|ください)[かねよ]?$|.ます[かねよ]$");

    // Textbook entries also list grammar on its own (でしょう, ください) and lesson forms: polite endings, the
    // ～たら/～ても/～たり/～ば/～なら/～ながら/～のに/～ないで/～るな patterns and a trailing を. The guards keep
    // とても, なんでも, ふたり, ひだり, たとえば, ことば and さようなら.
    private static final Pattern LESSON_FORM = Pattern.compile(
            "(?:(?:ま(?:す|した|せん|しょう)|で(?:す|した|しょう)|ください)[かねよ]?|たら|[くっ]ても|[^な]んでも"
                    + "|(?:した|った|んだ|いだ|いた)り|ながら|のに|ないで|(?<!よう)なら|(?<!と)[えけせてねへめれげべ]ば|るな|を)$"
                    + "|^たり$");

    /**
     * True for a reading that is a sentence or a lesson form (食べます, でしょう, いそがしくても, だれを, 大丈夫ですか)
     * rather than a word. The lesson-form rules only apply to textbook entries, which list words as the lesson used them.
     */
    public static boolean isLessonForm(String reading, boolean textbook) {
        if (reading == null) return false;
        String text = Normalizer.normalize(reading.strip(), Normalizer.Form.NFKC);
        return POLITE_FORM.matcher(text).find() || (textbook && LESSON_FORM.matcher(text).find());
    }

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
