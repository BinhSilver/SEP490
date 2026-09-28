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
     * from {@code batch} (canonical/approved data), never generated at runtime. */
    List<QuestionSpec> buildQuestions(List<T> batch, int batchIndex);
}
