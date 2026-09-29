package com.jlas.flashcard.speedrecall;

import com.jlas.flashcard.entity.FlashcardCard;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What a Speed Recall question shows and what the learner types back: any of the four card
 * fields, as long as question and answer differ. A card only joins a mode when it has both fields.
 * The legacy name KANJI_TO_READING (answer = HIRAGANA) is kept for sessions created before modes.
 */
public enum SpeedRecallMode {
    KANJI_TO_READING(Field.KANJI, Field.HIRAGANA),
    KANJI_TO_MEANING(Field.KANJI, Field.MEANING),
    KANJI_TO_HAN_VIET(Field.KANJI, Field.HAN_VIET),
    HIRAGANA_TO_KANJI(Field.HIRAGANA, Field.KANJI),
    HIRAGANA_TO_MEANING(Field.HIRAGANA, Field.MEANING),
    HIRAGANA_TO_HAN_VIET(Field.HIRAGANA, Field.HAN_VIET),
    MEANING_TO_KANJI(Field.MEANING, Field.KANJI),
    MEANING_TO_READING(Field.MEANING, Field.HIRAGANA),
    MEANING_TO_HAN_VIET(Field.MEANING, Field.HAN_VIET),
    HAN_VIET_TO_KANJI(Field.HAN_VIET, Field.KANJI),
    HAN_VIET_TO_READING(Field.HAN_VIET, Field.HIRAGANA),
    HAN_VIET_TO_MEANING(Field.HAN_VIET, Field.MEANING);

    /** Bean-validation pattern for request DTOs; kept in sync with the constants above. */
    public static final String PATTERN = "KANJI_TO_READING|KANJI_TO_MEANING|KANJI_TO_HAN_VIET"
            + "|HIRAGANA_TO_KANJI|HIRAGANA_TO_MEANING|HIRAGANA_TO_HAN_VIET"
            + "|MEANING_TO_KANJI|MEANING_TO_READING|MEANING_TO_HAN_VIET"
            + "|HAN_VIET_TO_KANJI|HAN_VIET_TO_READING|HAN_VIET_TO_MEANING";

    private static final Pattern HAN_CHAR = Pattern.compile("\\p{IsHan}");
    private static final Pattern KANA_ONLY = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}ー]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** Separators between alternative meanings / readings: "Cao, đắt" accepts "cao" or "đắt". */
    private static final Pattern ALTERNATIVES = Pattern.compile("[,;/、，；]");

    public enum Field {
        KANJI(FlashcardCard::getFront), HIRAGANA(FlashcardCard::getReading),
        MEANING(FlashcardCard::getMeaning), HAN_VIET(FlashcardCard::getHanViet);

        private final Function<FlashcardCard, String> read;

        Field(Function<FlashcardCard, String> read) { this.read = read; }

        public String of(FlashcardCard card) { return SpeedRecallRules.normalize(read.apply(card)); }

        /** Whether the card's value is usable for this field (shown as a question or graded as an answer). */
        boolean usable(String value) {
            if (value.isEmpty() || value.codePointCount(0, value.length()) > 500) return false;
            return switch (this) {
                // A kana-only word (e.g. katakana コーヒー) has no kanji: its front is just the reading,
                // so it is left out of Kanji modes but still plays Hiragana / Nghĩa / Hán Việt.
                case KANJI -> HAN_CHAR.matcher(value).find();
                case HIRAGANA -> value.length() <= 255 && KANA_ONLY.matcher(value).matches();
                case MEANING, HAN_VIET -> !fold(value).isEmpty();
            };
        }

        /** Vietnamese text is graded loosely; Japanese exactly. */
        boolean loose() { return this == MEANING || this == HAN_VIET; }
    }

    private final Field question;
    private final Field answer;

    SpeedRecallMode(Field question, Field answer) {
        this.question = question;
        this.answer = answer;
    }

    public Field question() { return question; }
    public Field answer() { return answer; }

    /** Unknown or missing values fall back to the original mode. */
    public static SpeedRecallMode parse(String value) {
        if (value == null || value.isBlank()) return KANJI_TO_READING;
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** A card joins this mode only when it has both the question and the answer field. */
    public boolean eligible(FlashcardCard card) {
        String q = question.of(card), a = answer.of(card);
        return question.usable(q) && answer.usable(a) && !comparable(q).equals(comparable(a));
    }

    /** Comparable form: exact kana/kanji, or Vietnamese without case, accents or extra spaces. */
    public String comparable(String text) {
        return answer.loose() ? fold(text) : SpeedRecallRules.normalize(text);
    }

    /** Correct when the input equals the expected answer or, for Vietnamese, any listed alternative. */
    public boolean matches(String input, String expected) {
        String value = comparable(input);
        if (value.isEmpty()) return false;
        if (value.equals(comparable(expected))) return true;
        return answer.loose() && Arrays.stream(ALTERNATIVES.split(expected))
                .map(SpeedRecallMode::fold).anyMatch(value::equals);
    }

    /** "ĐẠI", "Đại" and "dai" all fold to "dai". */
    static String fold(String text) {
        String value = Normalizer.normalize(SpeedRecallRules.normalize(text), Normalizer.Form.NFD);
        value = MARKS.matcher(value).replaceAll("").replace('đ', 'd').replace('Đ', 'D');
        return SPACES.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
