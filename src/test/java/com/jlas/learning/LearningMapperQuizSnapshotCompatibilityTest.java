package com.jlas.learning;

import com.jlas.learning.mapper.LearningMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LearningMapperQuizSnapshotCompatibilityTest {

    private final LearningMapper mapper = new LearningMapper();

    @Test
    void readsExistingListeningQuestionAndResultSnapshots() {
        String questions = """
            [{"questionId":2653,"skill":"LISTENING","jlptLevel":null,"title":null,
            "passageHtml":null,"audioUrl":null,"imageUrl":null,
            "questionText":"二人が最終的に合意したことは何ですか。",
            "options":["議題ごとに時間を決め、最後に自由な意見を聞く","全員が自由に話せるよう時間を決めないほうがよい"],
            "questionType":"MULTIPLE_CHOICE"}]
            """;
        String result = """
            {"totalQuestions":2,"correctAnswers":2,"scorePercent":100,"passed":true,
            "passThresholdPercent":70,"relearnRequired":false,
            "review":[{"questionId":2653,"correctOption":1,"selectedOption":1,"correct":true,
            "explanation":"説明"}],"groupResults":[]}
            """;

        assertEquals("LISTENING", mapper.readQuizQuestions(questions).getFirst().skill());
        assertEquals(100, mapper.readQuizResult(result).scorePercent());
        assertEquals("説明", mapper.readQuizResult(result).review().getFirst().explanation());
    }
}
