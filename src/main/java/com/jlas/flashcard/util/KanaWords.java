package com.jlas.flashcard.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules for words written without kanji (katakana loanwords such as コーヒー, or hiragana-only words).
 * Such a word has no kanji and no Hán Việt: its card front is simply the reading.
 */
public final class KanaWords {
    private static final Pattern HAN_CHAR = Pattern.compile("\\p{IsHan}");
    private static final Pattern KANA_ONLY = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}ー・\\s]+");

    /**
     * One import line: {@code Từ - Cách đọc : Nghĩa (Hán Việt)}. The reading part is optional so a kana word
     * can be written as {@code コーヒー : Cà phê}; the reading is then the word itself.
     */
    public static final Pattern IMPORT_LINE =
            Pattern.compile("^\\s*(.+?)\\s*(?:-\\s*(.+?)\\s*)?:\\s*(.+?)(?:\\s*\\(([^)]*)\\))?\\s*$");

    private KanaWords() {}

    public static boolean hasKanji(String text) {
        return text != null && HAN_CHAR.matcher(text).find();
    }

    /** True for a non-empty word made only of kana (katakana or hiragana). */
    public static boolean isKanaOnly(String text) {
        return text != null && !text.isBlank() && KANA_ONLY.matcher(text.strip()).matches();
    }

    public record ImportLine(String front, String reading, String meaning, String hanViet) {}

    /**
     * Parses one import line. The reading is optional and kept exactly as typed (possibly empty): only the
     * AI buttons fill it. A kana word never keeps a Hán Việt.
     */
    public static Optional<ImportLine> parseImportLine(String raw) {
        if (raw == null) return Optional.empty();
        Matcher m = IMPORT_LINE.matcher(raw.strip());
        if (!m.matches()) return Optional.empty();
        String front = m.group(1).strip();
        String reading = m.group(2) == null ? "" : m.group(2).strip();
        String meaning = m.group(3).strip();
        String hanViet = m.group(4) == null ? null : m.group(4).strip();
        boolean kana = !hasKanji(front) && isKanaOnly(front);
        if (kana) hanViet = null;
        return Optional.of(new ImportLine(front, reading, meaning, hanViet == null || hanViet.isBlank() ? null : hanViet));
    }
}
