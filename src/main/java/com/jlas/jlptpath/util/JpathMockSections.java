package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathMockSection;
import java.util.List;
import java.util.Locale;

/**
 * [JLAS-W0108] Policy #43 — maps a Mock exam part code ({@code JlptExamResponse.Part.code()}, the key of
 * {@code JlptMockResultResponse.sections()}) to a path section, case-insensitively, and gives the advice for a weak section.
 * Real codes in the content database (Plan 3 Task 1): {@code vocabulary}, {@code grammar_reading}, {@code listening}.
 */
public final class JpathMockSections {

    /** One piece of advice: a stable action code for the UI and its Vietnamese label. */
    public record Hint(String action, String labelVi) { }

    private static final List<String> VOCAB = List.of("VOCAB", "MOJI", "GOI", "KANJI", "LANGUAGE_KNOWLEDGE_1");
    private static final List<String> GRAMMAR_READING = List.of("GRAMMAR", "BUNPOU", "READ", "DOKKAI");
    private static final List<String> LISTENING = List.of("LISTEN", "CHOUKAI");

    private JpathMockSections() {
    }

    public static JpathMockSection of(String code) {
        if (code == null || code.isBlank()) {
            return JpathMockSection.OTHER;
        }
        String upper = code.toUpperCase(Locale.ROOT);
        if (containsAny(upper, VOCAB)) {
            return JpathMockSection.VOCAB;
        }
        if (containsAny(upper, GRAMMAR_READING)) {
            return JpathMockSection.GRAMMAR_READING;
        }
        if (containsAny(upper, LISTENING)) {
            return JpathMockSection.LISTENING;
        }
        return JpathMockSection.OTHER;
    }

    /** Advice for a weak section (#43); OTHER has none (the UI shows the part's own name only). */
    public static List<Hint> advice(JpathMockSection section) {
        return switch (section) {
            case VOCAB -> List.of(new Hint("NOTEBOOK_REVIEW", "Ôn sổ tay"),
                    new Hint("FREE_QUIZ_VOCAB_KANJI", "Quiz tự do Từ vựng/Kanji"));
            case GRAMMAR_READING -> List.of(new Hint("FREE_QUIZ_GRAMMAR", "Quiz tự do Ngữ pháp"),
                    new Hint("SKILL_READING", "Luyện Đọc (dạng điểm thấp nhất)"));
            case LISTENING -> List.of(new Hint("SKILL_LISTENING", "Luyện Nghe (dạng điểm thấp nhất)"));
            case OTHER -> List.of();
        };
    }

    private static boolean containsAny(String upper, List<String> needles) {
        for (String needle : needles) {
            if (upper.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
