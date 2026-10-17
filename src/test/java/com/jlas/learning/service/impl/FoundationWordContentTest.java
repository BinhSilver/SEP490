package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.learning.service.impl.engine.EngineStudyState;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FoundationWordContentTest {
    private static ContentItem item(long id, String lessonCode, String word, String romaji, String meaning) {
        var lesson = Lesson.builder().sourceCode(lessonCode).title("Âm ngắt với っ / ッ")
                .description("Đọc cả từ, chú ý âm ngắt.").build();
        return ContentItem.builder().id(id).section(LessonSection.builder().lesson(lesson).build())
                .contentType("READING").content(word).title(romaji).translatedText(meaning).build();
    }

    @Test void wordExamplesReachThePlayerWithLessonContextAndMeaning() {
        var word = item(1, "KANA-RULE-01", "きっぷ", "kippu", "vé");
        var state = new EngineStudyState<>(9L, LearningActivityType.FOUNDATION, LearningActivityState.STUDYING,
                "rev", 1, 0, 0, 1, false,
                List.of(new EngineStudyState.EngineCard<>(11L, 1, word, false, false)), 1, 1);

        var dto = InteractiveFoundationServiceImpl.toDto(state);

        assertEquals("EXAMPLE_WORD", dto.contentKind());
        assertEquals("Âm ngắt với っ / ッ", dto.lessonTitle());
        assertEquals("Đọc cả từ, chú ý âm ngắt.", dto.lessonDescription());
        assertEquals("きっぷ", dto.cards().getFirst().character());
        assertEquals("kippu", dto.cards().getFirst().romaji());
        assertEquals("vé", dto.cards().getFirst().meaningVi());
    }

    @Test void existingSingleCharacterLessonKeepsCharacterSemantics() {
        var kana = item(2, "KANA-H-01", "あ", "a", null);
        var state = new EngineStudyState<>(9L, LearningActivityType.FOUNDATION, LearningActivityState.STUDYING,
                "rev", 1, 0, 0, 1, false,
                List.of(new EngineStudyState.EngineCard<>(12L, 1, kana, false, false)), 1, 1);
        assertEquals("CHARACTER", InteractiveFoundationServiceImpl.toDto(state).contentKind());

        var adapter = new InteractiveFoundationServiceImpl.FoundationContentAdapter(null);
        var questions = adapter.buildQuestions(List.of(kana, item(3, "KANA-H-01", "か", "ka", null)), 0, 0);
        assertEquals("CHARACTER_TO_ROMAJI", questions.getFirst().type());
        assertEquals("Chữ “あ” đọc là gì?", questions.getFirst().prompt());
    }

    @Test void wordQuestionsAskAboutWholeWordsAndProvideMultipleDistinctOptions() {
        var adapter = new InteractiveFoundationServiceImpl.FoundationContentAdapter(null);
        var questions = adapter.buildQuestions(List.of(
                item(1, "KANA-RULE-01", "きっぷ", "kippu", "vé"),
                item(2, "KANA-RULE-01", "きって", "kitte", "tem")), 0, 0);

        assertEquals("WORD_TO_ROMAJI", questions.getFirst().type());
        assertEquals("Từ “きっぷ” đọc là gì?", questions.getFirst().prompt());
        assertTrue(questions.getFirst().options().containsAll(List.of("kippu", "kitte")));
        assertEquals("ROMAJI_TO_WORD", questions.get(1).type());
        assertEquals("Từ nào đọc là “kitte”?", questions.get(1).prompt());
        assertTrue(questions.get(1).options().containsAll(List.of("きっぷ", "きって")));
    }
}
