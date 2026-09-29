package com.jlas.learning.dto;

import java.util.List;

/** A single lesson-quiz question as shown to the learner (answers never included). */
public record QuizQuestionResponse(
        Long questionId,
        String questionType,
        String questionText,
        String passageHtml,
        List<String> options) {
}
