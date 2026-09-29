package com.jlas.learning.dto;

/**
 * One conversion rule on its own page, with the lesson and stage it belongs to.
 *
 * @param lessonStatus a {@link OnyomiLessonStatus} name for the rule's lesson
 */
public record OnyomiRulePageView(
        OnyomiLessonView.Rule rule,
        Long lessonId,
        int lessonOrder,
        String lessonTitle,
        String lessonStatus,
        int stageOrder,
        String stageTitle) {
}
