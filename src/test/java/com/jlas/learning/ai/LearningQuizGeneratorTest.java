package com.jlas.learning.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.learning.entity.LearningGeneratedQuestion;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.repository.LearningGeneratedQuestionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** [JLAS-W0108] "Ngân hàng trước, Gemini bù": fallback khi Gemini lỗi + lưu câu hợp lệ + idempotent. */
class LearningQuizGeneratorTest {

    private AiJsonGenerator aiJson;
    private LearningGeneratedQuestionRepository repository;
    private LearningQuizGenerator generator;
    private UserLearningPath path;

    @BeforeEach
    void setUp() {
        aiJson = mock(AiJsonGenerator.class);
        repository = mock(LearningGeneratedQuestionRepository.class);
        generator = new LearningQuizGenerator(aiJson, repository);
        path = UserLearningPath.builder().id(1L).build();
    }

    private static LearningQuizGenerator.Anchor anchor(String id) {
        return new LearningQuizGenerator.Anchor(
                LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT, id, "GRAMMAR", "N5", "～てください");
    }

    @Test
    void skipsAnchorAndSavesNothingWhenGeminiFails() {
        when(repository.existsByLearningPathIdAndAnchorTypeAndAnchorId(
                1L, LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT, "1")).thenReturn(false);
        when(aiJson.generateJson(anyString(), anyString())).thenReturn(null);

        var result = generator.generate(path, List.of(anchor("1")));

        assertTrue(result.isEmpty());
        verify(repository, never()).save(any());
    }

    @Test
    void skipsAnchorAlreadyGenerated() {
        when(repository.existsByLearningPathIdAndAnchorTypeAndAnchorId(
                1L, LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT, "1")).thenReturn(true);

        var result = generator.generate(path, List.of(anchor("1")));

        assertTrue(result.isEmpty());
        verify(aiJson, never()).generateJson(anyString(), anyString());
    }

    @Test
    void savesValidGeneratedQuestionWithNormalizedCorrectOption() {
        when(repository.existsByLearningPathIdAndAnchorTypeAndAnchorId(
                1L, LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT, "1")).thenReturn(false);
        var node = new ObjectMapper().createObjectNode();
        node.put("questionText", "Chọn trợ từ đúng cho câu sau.");
        var options = node.putArray("options");
        options.add("は").add("が").add("を").add("に");
        node.put("correctOptionIndex", 1); // 0-based -> lưu 2
        node.put("explanation", "Trợ từ chủ ngữ.");
        when(aiJson.generateJson(anyString(), anyString())).thenReturn(node);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = generator.generate(path, List.of(anchor("1")));

        assertEquals(1, result.size());
        LearningGeneratedQuestion q = result.get(0);
        assertEquals(LearningGeneratedQuestion.ANCHOR_GRAMMAR_POINT, q.getAnchorType());
        assertEquals("1", q.getAnchorId());
        assertEquals("Chọn trợ từ đúng cho câu sau.", q.getQuestionText());
        assertEquals("は", q.getOption1());
        assertEquals("が", q.getOption2());
        assertEquals("を", q.getOption3());
        assertEquals("に", q.getOption4());
        assertEquals(2, q.getCorrectOption());
        assertEquals("Trợ từ chủ ngữ.", q.getExplanation());
        assertEquals("ACTIVE", q.getStatus());
    }
}
