package com.jlas.learning.service.impl.text;

import com.atilika.kuromoji.ipadic.Token;

/**
 * Day 3 (24/10): JLPT dialogue lines from the N3 chapter book carry Kanji but no reviewed furigana (131/137 lines), so
 * Learning derives a reading aid from the IPADIC token readings, in the same {漢字|かんじ} markup as reviewed furigana.
 * It is shown as generated and unreviewed, never stored, and the line text itself is never changed.
 * <p>Okurigana shared by the surface and the reading stay outside the ruby (食べ → {食|た}べ); a token whose reading
 * is unknown is left without furigana rather than guessed.
 */
public final class GeneratedFurigana {
    private static final String KANJI = ".*[\\p{IsHan}々].*";

    private GeneratedFurigana() { }

    /** Furigana markup for {@code japanese}, or null when it has no Kanji or no reading could be derived. */
    public static String markup(String japanese) {
        if (japanese == null || !japanese.matches("(?s)" + KANJI)) return null;
        StringBuilder out = new StringBuilder();
        boolean any = false;
        boolean afterNumber = false;
        for (Token token : JapaneseTokenizer.tokenize(japanese)) {
            String surface = token.getSurface();
            String reading = token.getReading();
            // Numbers and the counter after them change reading together (二十分 = にじゅっぷん): token by token gives
            // に・じゅう・ふん, so they get no furigana rather than a wrong one.
            boolean number = "数".equals(token.getPartOfSpeechLevel2());
            boolean counter = afterNumber && "接尾".equals(token.getPartOfSpeechLevel2());
            afterNumber = number || (afterNumber && counter);
            // A one-Kanji prefix reads by what follows (今お時間 is いま, tagged as the prefix こん): no guess either.
            boolean prefix = "接頭詞".equals(token.getPartOfSpeechLevel1());
            if (number || counter || prefix || !surface.matches(KANJI) || reading == null || reading.isBlank() || "*".equals(reading)) {
                out.append(surface);
                continue;
            }
            String kana = toHiragana(reading);
            int head = 0;
            while (head < surface.length() && head < kana.length() && isKana(surface.charAt(head))
                    && toHiragana(String.valueOf(surface.charAt(head))).charAt(0) == kana.charAt(head)) head++;
            int tail = 0;
            while (tail < surface.length() - head && tail < kana.length() - head
                    && isKana(surface.charAt(surface.length() - 1 - tail))
                    && toHiragana(String.valueOf(surface.charAt(surface.length() - 1 - tail))).charAt(0)
                        == kana.charAt(kana.length() - 1 - tail)) tail++;
            String base = surface.substring(head, surface.length() - tail);
            String ruby = kana.substring(head, kana.length() - tail);
            if (base.isEmpty() || ruby.isEmpty() || base.contains("{") || base.contains("|") || base.contains("}")) {
                out.append(surface);
                continue;
            }
            out.append(surface, 0, head).append('{').append(base).append('|').append(ruby).append('}')
                    .append(surface, surface.length() - tail, surface.length());
            any = true;
        }
        return any ? out.toString() : null;
    }

    private static boolean isKana(char c) {
        return (c >= 'ぁ' && c <= 'ゖ') || (c >= 'ァ' && c <= 'ヶ');
    }

    private static String toHiragana(String katakana) {
        StringBuilder out = new StringBuilder(katakana.length());
        for (char c : katakana.toCharArray()) out.append(c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c);
        return out.toString();
    }
}
