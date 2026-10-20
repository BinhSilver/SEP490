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
        String transcriptJa,
        /** Learning-owned source group (e.g. {@code LP-OWNED-SPEED-N3-C01-READ-P1}) or null for bank exercises. */
        String ownedSourceGroup) {
    public ComprehensionExercise {
        questions = List.copyOf(questions);
        if (displaySource == null) displaySource = source;
    }
    public ComprehensionExercise(String fingerprint, String skill, String jlptLevel, String source, String outcomeCode,
            String topicCode, List<ComprehensionQuestionSpec> questions, String sourceRevision) {
        this(fingerprint, skill, jlptLevel, source, outcomeCode, topicCode, questions, sourceRevision, source, null, null);
    }
    public ComprehensionExercise(String fingerprint, String skill, String jlptLevel, String source, String outcomeCode,
            String topicCode, List<ComprehensionQuestionSpec> questions, String sourceRevision, String displaySource) {
        this(fingerprint, skill, jlptLevel, source, outcomeCode, topicCode, questions, sourceRevision, displaySource, null, null);
    }
    public ComprehensionExercise(String fingerprint, String skill, String jlptLevel, String source, String outcomeCode,
            String topicCode, List<ComprehensionQuestionSpec> questions, String sourceRevision, String displaySource,
            String transcriptJa) {
        this(fingerprint, skill, jlptLevel, source, outcomeCode, topicCode, questions, sourceRevision, displaySource,
                transcriptJa, null);
    }

    /** Speed Master N3 passages and clips belong to their J-LAS N3 chapter (review 2026-10-20), not to warm-ups. */
    public boolean chapterBoundSpeedN3() {
        return ownedSourceGroup != null && ownedSourceGroup.startsWith("LP-OWNED-SPEED-N3-");
    }
}
