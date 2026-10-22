package com.jlas.learning.dto;

import java.util.List;

/**
 * A single lesson-quiz question as shown to the learner (answers never included).
 *
 * @param focus the kanji or word the question is about, shown large on the card; null when the
 *              question has none or when showing it would give the answer away
 */
public record QuizQuestionResponse(
        Long questionId,
        String questionType,
        String questionText,
        String passageHtml,
        String imageUrl,
        List<String> options,
        String focus) {
}
