package com.jlas.learning.service.impl.engine;

import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;

import java.util.List;
import java.util.Map;

/** [JLAS-W0193] The minimal seam a content type must supply to reuse {@link InteractiveStudyEngine}. Everything
 * state-machine related (session/card lifecycle, flip/remember, attempt cycles, cooldown, review, idempotent
 * submit, grading) lives in the engine; only "what is a canonical target" and "how do I ask about it" vary. */
public interface InteractiveContentAdapter<T> {
    /** The {@link com.jlas.learning.entity.UserLearningPathItem} activity type this content studies under. */
    LearningActivityType activityType();

    /** The {@link com.jlas.learning.entity.LearningActivityMember} member type that scopes this content's
     * membership rows — never trust a client-supplied id list, always re-derive from membership. */
    LearningMemberType memberType();

    /** Discriminator stored on {@link com.jlas.learning.entity.LearningQuizSession#getQuizType()} so
     * Vocabulary and Kanji micro-check sessions never collide. */
    String microCheckType();

    /** Bulk-loads canonical entities for the given membership ids, keyed by id. Missing ids are simply
     * absent from the map (never fabricated). */
    Map<Long, T> loadByIds(List<Long> canonicalIds);

    /** Builds the deterministic, no-AI question set for one micro-check batch. Distractors must come only
     * from {@code batch} (canonical/approved data), never generated at runtime.
     *
     * <p>[JLAS-W0193] Task 10 audit finding — {@code variationSeed} (the batch's {@code attemptsUsed} count
     * at issue time, server-derived, never client-supplied) must shift the option rotation/type assignment
     * between successive issues of the *same* batch. Before this parameter existed, generation depended only
     * on {@code (batch, batchIndex)}, which never changes across retries of an unpassed batch — so a failed
     * attempt's required feedback (design spec §4.5, which reveals the correct answer per question in
     * {@code MicroCheckResult.review}) could be replayed verbatim on the very next issue, letting a learner
     * pass without engaging with the content at all. Implementations must fold {@code variationSeed} into
     * their rotation math (see {@code InteractiveVocabularyServiceImpl}/{@code InteractiveKanjiServiceImpl}/
     * {@code InteractiveGrammarServiceImpl} for the pattern) rather than ignoring it. */
    List<QuestionSpec> buildQuestions(List<T> batch, int batchIndex, int variationSeed);

    /** [JLAS-W0193] Task 6 — whether {@link InteractiveStudyEngine#issueMicroCheck} should gate eligibility
     * on the flip/remember study-card contract (Vocabulary, Kanji). Default {@code true} preserves the
     * exact pre-Task-6 behavior for every existing adapter with zero code change. A content type whose
     * prerequisite is not flip/remember — Grammar's ordering + fill practice — overrides this to
     * {@code false} and supplies {@link #assertPracticeGateSatisfied} instead. */
    default boolean requiresFlipRememberGate() {
        return true;
    }

    /** [JLAS-W0193] Task 6 — only consulted when {@link #requiresFlipRememberGate()} is {@code false}.
     * Must throw {@code ApiException(ErrorCode.PREREQUISITE_NOT_MET)} when the content type's own practice
     * gate is not yet satisfied for every membership target. No-op default (unused by any adapter that
     * keeps the flip/remember gate). */
    default void assertPracticeGateSatisfied(Long activityId, List<com.jlas.learning.entity.LearningActivityMember> membership) {
    }
}
