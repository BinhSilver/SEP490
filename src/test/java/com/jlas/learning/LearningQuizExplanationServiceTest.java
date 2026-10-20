package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.common.exception.ApiException;
import com.jlas.learning.ai.AiJsonGenerator;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.service.LearningQuizExplanationService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LearningQuizExplanationServiceTest {
    private final LearningQuizSessionRepository sessions = mock(LearningQuizSessionRepository.class);
    private final LearningMapper mapper = new LearningMapper();
    private final AiJsonGenerator ai = mock(AiJsonGenerator.class);
    private final LearningQuizExplanationService service = new LearningQuizExplanationService(sessions, mapper, ai);

    @Test
    void explainsOnlyFromOwnedSubmittedAttemptSnapshot() {
        var question = new com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion(77L, "VOCABULARY", "N5",
                null, null, null, null, "静かの意味は？", java.util.List.of("にぎやか", "しずか"), "SINGLE_CHOICE");
        var session = LearningQuizSession.builder().id(12L).status("SUBMITTED")
                .questionsJson(mapper.writeQuizQuestions(java.util.List.of(question)))
                .answerKeyJson(mapper.writeQuizAnswerKey(Map.of(77L,
                        new com.jlas.learning.dto.QuizAnswerKey(2, "形容動詞です", null, null))))
                .resultJson(mapper.writeQuizResult(new com.jlas.learning.dto.LessonQuizResult(1, 1, 100,
                        true, 70, false, java.util.List.of(new com.jlas.learning.dto.LessonQuizResult.AnswerReview(
                                77L, 2, 2, true, null)), java.util.List.of())))
                .build();
        when(sessions.findByIdAndUserIdAndActivityId(12L, 7L, 50L)).thenReturn(Optional.of(session));
        ObjectNode response = new ObjectMapper().createObjectNode().put("explanation", "静か（しずか）は静かな様子です。")
                .put("correctAnswerTranslationVi", "yên tĩnh")
                .put("exampleJa", "この町は静かです。")
                .put("exampleVi", "Thị trấn này yên tĩnh.");
        when(ai.generateJson(anyString(), anyString())).thenReturn(response);

        var result = service.explain(7L, 50L, 12L, 77L);

        assertEquals("AI", result.source());
        assertTrue(result.explanation().contains("静かな様子"));
        assertEquals("yên tĩnh", result.correctAnswerTranslationVi());
        assertEquals("この町は静かです。", result.exampleJa());
        assertEquals("Thị trấn này yên tĩnh.", result.exampleVi());
        verify(ai).generateJson(argThat(system -> system.contains("ví dụ mới") && system.contains("dịch tiếng Việt")),
                argThat(prompt -> prompt.contains("静かの意味")
                && prompt.contains("しずか") && prompt.contains("形容動詞です")));
    }

    @Test
    void usesOnlyFrozenListeningScriptForOptionalPostAnswerTranslation() {
        var question = new com.jlas.learning.dto.LessonQuizResponse.LessonQuizQuestion(3L, "LISTENING", "N5",
                null, null, null, null, "いつですか。", java.util.List.of("月曜日", "火曜日"), "SINGLE_CHOICE");
        var session = LearningQuizSession.builder().id(22L).status("SUBMITTED")
                .questionsJson(mapper.writeQuizQuestions(java.util.List.of(question)))
                .answerKeyJson(mapper.writeQuizAnswerKey(Map.of(3L,
                        new com.jlas.learning.dto.QuizAnswerKey(2, "火曜日と言いました", null, null))))
                .resultJson(mapper.writeQuizResult(new com.jlas.learning.dto.LessonQuizResult(1, 1, 100,
                        true, 70, false, java.util.List.of(new com.jlas.learning.dto.LessonQuizResult.AnswerReview(
                                3L, 2, 2, true, null)), java.util.List.of())))
                .sourceSnapshotJson("{\"audioUrl\":\"/audio/a.mp3\",\"transcriptJa\":\"女：火曜日です。\"}")
                .build();
        when(sessions.findByIdAndUserIdAndActivityId(22L, 7L, 50L)).thenReturn(Optional.of(session));
        when(ai.generateJson(anyString(), anyString())).thenReturn(new ObjectMapper().createObjectNode()
                .put("explanation", "Người nói nhắc đến thứ Ba.")
                .put("scriptTranslationVi", "Nữ: Là thứ Ba."));
        var result = service.explain(7L, 50L, 22L, 3L);
        assertEquals("Nữ: Là thứ Ba.", result.scriptTranslationVi());
        verify(ai).generateJson(anyString(), argThat(prompt -> prompt.contains("女：火曜日です。")));
    }

    @Test
    void refusesAttemptsThatDoNotBelongToTheRequestedUserAndActivity() {
        when(sessions.findByIdAndUserIdAndActivityId(12L, 8L, 50L)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.explain(8L, 50L, 12L, 77L));
        verifyNoInteractions(ai);
    }
}
