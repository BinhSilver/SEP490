package com.jlas.game.enums;

import com.jlas.flashcard.entity.FlashcardCard;

/**
 * Trường nội dung của một thẻ flashcard dùng làm câu hỏi hoặc đáp án trong game.
 * Khoá KANJI/HIRAGANA/MEANING trùng với trang quiz flashcard; thêm HAN_VIET vì thẻ có 4 trường.
 */
public enum DeckField {
    KANJI("Kanji", true),
    HIRAGANA("Cách đọc", true),
    MEANING("Nghĩa tiếng Việt", false),
    HAN_VIET("Hán Việt", false);

    /** Thẻ cũ lưu {@code back} dạng "cách đọc — nghĩa". */
    private static final String LEGACY_BACK_SEPARATOR = " — ";

    private final String label;
    private final boolean japanese;

    DeckField(String label, boolean japanese) {
        this.label = label;
        this.japanese = japanese;
    }

    public String label() {
        return label;
    }

    /** Nội dung tiếng Nhật: UI dùng font Nhật, đáp án gõ chấp nhận romaji. */
    public boolean japanese() {
        return japanese;
    }

    /** Giá trị của trường trên thẻ, đã gọn khoảng trắng; {@code null} nếu thẻ bỏ trống trường này. */
    public String read(FlashcardCard card) {
        String value = switch (this) {
            case KANJI -> card.getFront();
            case HIRAGANA -> firstText(card.getReading(), legacyBack(card.getBack(), true));
            case MEANING -> firstText(card.getMeaning(), legacyBack(card.getBack(), false));
            case HAN_VIET -> card.getHanViet();
        };
        return clean(value);
    }

    private static String legacyBack(String back, boolean readingPart) {
        if (back == null) {
            return null;
        }
        int split = back.indexOf(LEGACY_BACK_SEPARATOR);
        if (split < 0) {
            return readingPart ? null : back;
        }
        return readingPart ? back.substring(0, split) : back.substring(split + LEGACY_BACK_SEPARATOR.length());
    }

    private static String firstText(String preferred, String fallback) {
        return clean(preferred) != null ? preferred : fallback;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.replaceAll("\\s+", " ").trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
