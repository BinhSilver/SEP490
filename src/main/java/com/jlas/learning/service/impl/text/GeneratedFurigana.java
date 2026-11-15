package com.jlas.learning.service.impl.text;

import com.atilika.kuromoji.ipadic.Token;
import java.util.List;

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
        boolean afterDirectNumber = false;
        boolean afterDigitNumber = false;
        List<Token> tokens = JapaneseTokenizer.tokenize(japanese);
        for (int t = 0; t < tokens.size(); t++) {
            Token token = tokens.get(t);
            String surface = token.getSurface();
            String reading = token.getReading();
            // F107: before a noun IPADIC reads 来る as the adnominal きたる (来る人 → きたるひと); learners mean the verb
            // くる. Only a date keeps きたる (来る10月).
            if ("来る".equals(surface) && "連体詞".equals(token.getPartOfSpeechLevel1())
                    && (t + 1 >= tokens.size() || !"数".equals(tokens.get(t + 1).getPartOfSpeechLevel2()))) {
                reading = "クル";
            }
            boolean number = "数".equals(token.getPartOfSpeechLevel2());
            boolean kanjiNumber = number && KanjiNumberReadings.isKanjiNumeral(surface);
            // F153: Kanji numerals (not immediately after a digit number) and their counter are read together via
            // KanjiNumberReadings (二十分 = にじゅっぷん, 三階 = さんがい); unknown/ambiguous spans stay plain.
            if (kanjiNumber && !afterDigitNumber) {
                int end = t + 1;
                while (end < tokens.size() && isKanjiNumberToken(tokens.get(end))) end++;
                List<Token> numTokens = tokens.subList(t, end);
                Token counterToken = null;
                if (end < tokens.size() && isKanjiNumberCounter(tokens.get(end))) {
                    counterToken = tokens.get(end);
                    end++;
                }
                StringBuilder span = new StringBuilder();
                for (Token nt : numTokens) span.append(nt.getSurface());
                if (counterToken != null) span.append(counterToken.getSurface());
                String spanSurface = span.toString();
                String kana = KanjiNumberReadings.read(numTokens, counterToken);
                if (kana == null || !spanSurface.matches(KANJI)) {
                    out.append(spanSurface);
                    afterDigitNumber = false;
                    afterDirectNumber = counterToken == null;
                    afterNumber = true;
                } else {
                    if (appendRuby(out, spanSurface, kana)) any = true;
                    afterDigitNumber = false;
                    afterDirectNumber = false;
                    afterNumber = false;
                }
                t = end - 1;
                continue;
            }
            // Digit numbers and the counter after them stay plain rather than guessing (F124: 10月 = 10 + plain noun 月).
            boolean counter = afterNumber && ("接尾".equals(token.getPartOfSpeechLevel2())
                    || (afterDirectNumber && "名詞".equals(token.getPartOfSpeechLevel1())
                        && surface.length() == 1 && surface.matches(KANJI)));
            afterDigitNumber = number && (!kanjiNumber || afterDigitNumber);
            afterDirectNumber = number;
            afterNumber = number || (afterNumber && counter);
            // A one-Kanji prefix reads by what follows (今お時間 is いま, tagged as the prefix こん): no guess either.
            boolean prefix = "接頭詞".equals(token.getPartOfSpeechLevel1());
            if (number || counter || prefix || !surface.matches(KANJI) || reading == null || reading.isBlank() || "*".equals(reading)) {
                out.append(surface);
                continue;
            }
            if (appendRuby(out, surface, toHiragana(reading))) any = true;
        }
        return any ? out.toString() : null;
    }

    private static boolean isKanjiNumberToken(Token token) {
        return "数".equals(token.getPartOfSpeechLevel2()) && KanjiNumberReadings.isKanjiNumeral(token.getSurface());
    }

    private static boolean isKanjiNumberCounter(Token token) {
        String pos1 = token.getPartOfSpeechLevel1();
        String pos2 = token.getPartOfSpeechLevel2();
        String surface = token.getSurface();
        return "接尾".equals(pos2)
                || (surface.length() == 1 && "名詞".equals(pos1) && KanjiNumberReadings.isKnownNounCounter(surface));
    }

    private static boolean appendRuby(StringBuilder out, String surface, String kana) {
        int head = 0;
        while (head < surface.length() && head < kana.length() && isKana(surface.charAt(head))
                && toHiraganaChar(surface.charAt(head)) == kana.charAt(head)) head++;
        int tail = 0;
        while (tail < surface.length() - head && tail < kana.length() - head
                && isKana(surface.charAt(surface.length() - 1 - tail))
                && toHiraganaChar(surface.charAt(surface.length() - 1 - tail))
                    == kana.charAt(kana.length() - 1 - tail)) tail++;
        String base = surface.substring(head, surface.length() - tail);
        String ruby = kana.substring(head, kana.length() - tail);
        if (base.isEmpty() || ruby.isEmpty() || base.contains("{") || base.contains("|") || base.contains("}")) {
            out.append(surface);
            return false;
        }
        out.append(surface, 0, head).append('{').append(base).append('|').append(ruby).append('}')
                .append(surface, surface.length() - tail, surface.length());
        return true;
    }

    private static boolean isKana(char c) {
        return (c >= 'ぁ' && c <= 'ゖ') || (c >= 'ァ' && c <= 'ヶ') || c == 'ー';
    }

    private static char toHiraganaChar(char c) {
        return c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c;
    }

    static String toHiragana(String katakana) {
        StringBuilder out = new StringBuilder(katakana.length());
        for (char c : katakana.toCharArray()) out.append(toHiraganaChar(c));
        return out.toString();
    }
}
