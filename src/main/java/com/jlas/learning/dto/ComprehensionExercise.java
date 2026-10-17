package com.jlas.learning.dto;

import java.util.List;

/** A deterministic, playable Reading or Listening exercise resolved from the canonical question bank. */
public record ComprehensionExercise(
        String fingerprint,
        String skill,
        String jlptLevel,
        String source,
        String outcomeCode,
        String topicCode,
        List<ComprehensionQuestionSpec> questions,
        String sourceRevision,
        /** What the learner sees: a Reading passage with its paragraph breaks. {@code source} stays the flattened
         * text the fingerprint/revision is computed from, so display changes never invalidate an activity. */
        String displaySource,
        /** Japanese script associated with this listening source; not shown before answering. */
        String transcriptJa) {
    public ComprehensionExercise {
        questions = List.copyOf(questions);
        if (displaySource == null) displaySource = source;
    }
    public ComprehensionExercise(String fingerprint, String skill, String jlptLevel, String source, String outcomeCode,
            String topicCode, List<ComprehensionQuestionSpec> questions, String sourceRevision) {
        this(fingerprint, skill, jlptLevel, source, outcomeCode, topicCode, questions, sourceRevision, source, null);
    }
    public ComprehensionExercise(String fingerprint, String skill, String jlptLevel, String source, String outcomeCode,
            String topicCode, List<ComprehensionQuestionSpec> questions, String sourceRevision, String displaySource) {
        this(fingerprint, skill, jlptLevel, source, outcomeCode, topicCode, questions, sourceRevision, displaySource, null);
    }
}
