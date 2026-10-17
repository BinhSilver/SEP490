package com.jlas.learning.service.impl;

import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.learning.service.impl.engine.QuestionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for the N3 activity-4 collision (夜中 / 真夜中 both meaning "giữa đêm"): a reverse question whose
 * target meaning or reading is shared by another word in the batch has two valid answers, so it must fall back to
 * the always-unambiguous word→meaning direction instead. */
class VocabularyQuestionGenerationTest {

    private final InteractiveVocabularyServiceImpl.VocabularyContentAdapter adapter =
        new InteractiveVocabularyServiceImpl.VocabularyContentAdapter(null);

    private static VocabularyEntry word(long id, String expression, String reading, String meaning) {
        return VocabularyEntry.builder().id(id).expression(expression).normalizedExpression(expression)
            .reading(reading).normalizedReading(reading).meaning(meaning).build();
    }

    @Test
    void duplicatedMeaningNeverProducesAnAmbiguousReverseQuestion() {
        List<VocabularyEntry> batch = List.of(
            word(1L, "夜中", "よなか", "giữa đêm"),
            word(2L, "真夜中", "まよなか", "giữa đêm"),
            word(3L, "朝", "あさ", "buổi sáng"));

        for (int seed = 0; seed < 3; seed++) {
            List<QuestionSpec> questions = adapter.buildQuestions(batch, 0, seed);
            assertTrue(questions.stream().noneMatch(q ->
                "MEANING_TO_WORD".equals(q.type()) && q.prompt().contains("giữa đêm")),
                "seed " + seed + ": a duplicated meaning must fall back, not ask an ambiguous reverse question");
            assertTrue(questions.stream().noneMatch(q -> "MATCHING".equals(q.type())),
                "seed " + seed + ": MATCHING stays skipped when meanings are not all distinct");
        }
    }

    @Test
    void duplicatedReadingNeverProducesAnAmbiguousReverseQuestion() {
        List<VocabularyEntry> batch = List.of(
            word(1L, "花", "はな", "hoa"),
            word(2L, "鼻", "はな", "mũi"),
            word(3L, "目", "め", "mắt"));

        for (int seed = 0; seed < 3; seed++) {
            List<QuestionSpec> questions = adapter.buildQuestions(batch, 0, seed);
            assertTrue(questions.stream().noneMatch(q ->
                "READING_TO_WORD".equals(q.type()) && q.prompt().contains("はな")),
                "seed " + seed + ": a duplicated reading must fall back, not ask an ambiguous reverse question");
        }
    }

    @Test
    void uniqueMeaningsKeepAllThreeSelectTypesAndMatching() {
        List<VocabularyEntry> batch = List.of(
            word(1L, "学生", "がくせい", "học sinh"),
            word(2L, "先生", "せんせい", "giáo viên"),
            word(3L, "本", "ほん", "sách"));

        for (int seed = 0; seed < 3; seed++) {
            List<QuestionSpec> questions = adapter.buildQuestions(batch, 0, seed);
            assertEquals(6, questions.size(), "3 select questions + 3 MATCHING for a unique batch");
            assertTrue(questions.stream().anyMatch(q -> "WORD_TO_MEANING".equals(q.type())));
            assertTrue(questions.stream().anyMatch(q -> "READING_TO_WORD".equals(q.type())));
            assertTrue(questions.stream().anyMatch(q -> "MEANING_TO_WORD".equals(q.type())));
            assertTrue(questions.stream().anyMatch(q -> "MATCHING".equals(q.type())));
        }
    }
}
