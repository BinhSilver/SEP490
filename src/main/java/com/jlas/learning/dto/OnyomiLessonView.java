package com.jlas.learning.dto;

import java.util.List;

/**
 * Everything the Onyomi lesson page shows: the conversion rules with their teaching steps, examples
 * and exceptions, the size of the practice quiz, and where the learner can go next.
 *
 * @param status        a {@link OnyomiLessonStatus} name
 * @param bestScore     best practice-quiz score in percent, null before the first submitted attempt
 * @param next          the next lesson of the course; null for the last lesson
 * @param lastInStage   true when finishing this lesson completes its stage (the final test comes next)
 */
public record OnyomiLessonView(
        Long lessonId,
        int order,
        String title,
        String description,
        Integer estimatedMinutes,
        Long unitId,
        int stageOrder,
        String stageTitle,
        String status,
        Integer bestScore,
        List<Rule> rules,
        int exampleCount,
        int practiceQuestionCount,
        LessonLink previous,
        LessonLink next,
        boolean lastInStage,
        boolean finalUnlocked) {

    /** A conversion rule (source sound → target sound) with its teaching material. */
    public record Rule(
            Long ruleId,
            String sourceSound,
            String targetSound,
            String title,
            String description,
            String tip,
            String note,
            List<Step> steps,
            List<Example> examples,
            List<Example> exceptions) {
    }

    /** One step of the rule's explanation (Nhận diện, Chuyển đổi, Ghi nhớ, Lưu ý). */
    public record Step(int step, String title, String content) {
    }

    /**
     * A kanji the rule applies to (or, for an exception, one it does not). {@code reading} is the On
     * reading in katakana; {@code word} / {@code wordReading} / {@code meaning} describe an example word.
     */
    public record Example(
            String kanji,
            String hanViet,
            String reading,
            String romaji,
            String word,
            String wordReading,
            String meaning,
            String note) {
    }

    /** A link to another lesson of the course. */
    public record LessonLink(Long lessonId, int stageOrder, int order, String title, boolean unlocked) {
    }
}
