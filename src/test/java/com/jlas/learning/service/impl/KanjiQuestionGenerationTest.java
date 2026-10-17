package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.Kanji;
import com.jlas.learning.service.impl.engine.QuestionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same ambiguity class as the vocabulary fix: on-readings are frequently shared across kanji, so a
 * {@code READING_TO_KANJI} ("which kanji reads X?") question would have two valid answers when two kanji in the
 * batch share the primary reading. It must fall back to an unambiguous direction instead. */
class KanjiQuestionGenerationTest {

    private final InteractiveKanjiServiceImpl.KanjiContentAdapter adapter =
        new InteractiveKanjiServiceImpl.KanjiContentAdapter(null, null);

    private static InteractiveKanjiServiceImpl.KanjiEntry kanji(long id, String character, String meaning,
            List<String> on, List<String> kun) {
        Kanji k = Kanji.builder().id(id).character(character).meaningVi(meaning).build();
        return new InteractiveKanjiServiceImpl.KanjiEntry(k, on, kun);
    }

    @Test
    void duplicatedPrimaryReadingNeverProducesAnAmbiguousReadingToKanjiQuestion() {
        List<InteractiveKanjiServiceImpl.KanjiEntry> batch = List.of(
            kanji(1L, "花", "hoa", List.of("カ"), List.of("はな")),
            kanji(2L, "化", "biến hóa", List.of("カ"), List.of("ば")),
            kanji(3L, "目", "mắt", List.of("モク"), List.of("め")));

        for (int seed = 0; seed < 3; seed++) {
            List<QuestionSpec> questions = adapter.buildQuestions(batch, 0, seed);
            assertTrue(questions.stream().noneMatch(q ->
                "READING_TO_KANJI".equals(q.type()) && q.prompt().contains("カ")),
                "seed " + seed + ": a shared on-reading must fall back, not ask an ambiguous reading→kanji question");
            assertTrue(questions.stream().anyMatch(q -> q.prompt().contains("花")),
                "seed " + seed + ": 花 must still be asked");
            assertTrue(questions.stream().anyMatch(q -> q.prompt().contains("化")),
                "seed " + seed + ": 化 must still be asked");
        }
    }

    @Test
    void uniqueReadingsKeepAllThreeSelectShapes() {
        List<InteractiveKanjiServiceImpl.KanjiEntry> batch = List.of(
            kanji(1L, "花", "hoa", List.of("カ"), List.of("はな")),
            kanji(2L, "目", "mắt", List.of("モク"), List.of("め")),
            kanji(3L, "水", "nước", List.of("スイ"), List.of("みず")));

        for (int seed = 0; seed < 3; seed++) {
            List<QuestionSpec> questions = adapter.buildQuestions(batch, 0, seed);
            assertTrue(questions.stream().anyMatch(q -> "KANJI_TO_READING".equals(q.type())));
            assertTrue(questions.stream().anyMatch(q -> "READING_TO_KANJI".equals(q.type())));
            assertTrue(questions.stream().anyMatch(q -> "KANJI_TO_MEANING".equals(q.type())));
        }
    }
}
