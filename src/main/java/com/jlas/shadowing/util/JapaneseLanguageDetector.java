package com.jlas.shadowing.util;

import java.util.List;

/**
 * Lightweight safety check used before a shadowing lesson is created.
 * It is intentionally conservative: Japanese subtitle tracks are checked by
 * their language code, while speech transcripts are checked for Japanese script.
 */
public final class JapaneseLanguageDetector {

    private JapaneseLanguageDetector() {
    }

    public record Result(boolean japanese, boolean nonJapanese, double confidence, String reason) {
        public boolean isUnknown() {
            return !japanese && !nonJapanese;
        }
    }

    public static Result inspect(String text) {
        if (text == null || text.isBlank()) {
            return new Result(false, false, 0, "Chưa có đủ văn bản để kiểm tra ngôn ngữ.");
        }

        int kana = 0;
        int kanji = 0;
        int latin = 0;
        int otherForeign = 0;
        int meaningful = 0;

        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isDigit(cp) || isPunctuation(cp)) continue;

            if (isHiragana(cp) || isKatakana(cp)) {
                kana++;
                meaningful++;
            } else if (isCjk(cp)) {
                kanji++;
                meaningful++;
            } else if (isLatin(cp)) {
                latin++;
                meaningful++;
            } else if (Character.isLetter(cp)) {
                otherForeign++;
                meaningful++;
            }
        }

        int japaneseSignals = kana + kanji;
        int foreignSignals = latin + otherForeign;

        // A few kana characters are a stronger signal than a title or a name.
        if (kana >= 2 || (kana >= 1 && kanji >= 2 && japaneseSignals >= 4)) {
            double confidence = kana >= 4 ? 0.96 : 0.86;
            return new Result(true, false, confidence, "Transcript có ký tự Hiragana/Katakana của tiếng Nhật.");
        }

        // CJK without kana is much more likely to be Chinese than Japanese speech.
        if (kanji >= 5 && kana == 0) {
            return new Result(false, true, 0.88, "Nội dung có dấu hiệu tiếng Trung hoặc ngôn ngữ CJK khác, không đủ dấu hiệu tiếng Nhật.");
        }

        if (foreignSignals >= 5 && foreignSignals > japaneseSignals * 2) {
            return new Result(false, true, 0.92, "Transcript chủ yếu là ký tự Latin hoặc ngôn ngữ khác tiếng Nhật.");
        }

        if (meaningful >= 6 && japaneseSignals == 0) {
            return new Result(false, true, 0.78, "Không phát hiện ký tự tiếng Nhật trong transcript.");
        }

        return new Result(false, false, 0, "Chưa đủ tín hiệu để kết luận ngôn ngữ.");
    }

    public static Result inspect(List<String> texts) {
        if (texts == null || texts.isEmpty()) return inspect("");
        return inspect(String.join(" ", texts));
    }

    private static boolean isHiragana(int cp) {
        return cp >= 0x3040 && cp <= 0x309F;
    }

    private static boolean isKatakana(int cp) {
        return cp >= 0x30A0 && cp <= 0x30FF;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF);
    }

    private static boolean isLatin(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    private static boolean isPunctuation(int cp) {
        return Character.isWhitespace(cp) || Character.getType(cp) == Character.CONNECTOR_PUNCTUATION
                || Character.getType(cp) == Character.DASH_PUNCTUATION
                || Character.getType(cp) == Character.START_PUNCTUATION
                || Character.getType(cp) == Character.END_PUNCTUATION
                || Character.getType(cp) == Character.OTHER_PUNCTUATION
                || Character.getType(cp) == Character.INITIAL_QUOTE_PUNCTUATION
                || Character.getType(cp) == Character.FINAL_QUOTE_PUNCTUATION;
    }
}
