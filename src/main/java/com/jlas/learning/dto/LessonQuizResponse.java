package com.jlas.learning.dto;

import java.util.List;

/**
 * [JLAS-W0113] Câu hỏi quiz của một lesson (không lộ đáp án chuẩn cho client).
 */
public record LessonQuizResponse(Long lessonId, String lessonTitle, List<LessonQuizQuestion> questions) {
    public LessonQuizResponse {
        questions = List.copyOf(questions);
    }

    public record LessonQuizQuestion(
            Long questionId,
            String skill,
            String jlptLevel,
            String title,
            String passageHtml,
            String audioUrl,
            String imageUrl,
            String questionText,
            List<String> options
    ) {
        public LessonQuizQuestion {
            options = List.copyOf(options);
        }
    }
}
