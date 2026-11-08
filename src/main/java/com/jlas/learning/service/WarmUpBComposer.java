package com.jlas.learning.service;

import com.jlas.learning.dto.InteractiveCompositionResult;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.entity.UserLearningPath;

/**
 * [JLAS-W0193] Task 9 — converts PROVEN Smart Test weakness evidence (never the whole source lesson)
 * into V2 Warm-up B packages/activities that the existing Vocabulary/Kanji/Grammar engine studies and
 * micro-checks unchanged. Reading/Listening banding is not composed into persisted activities yet (see
 * {@link com.jlas.learning.service.impl.ComprehensionWarmUpPlanner} and the Task 9 handoff) — only
 * Vocabulary, Kanji and Grammar weak items are scheduled by this composer.
 */
public interface WarmUpBComposer {
    InteractiveCompositionResult composeWarmUp(UserLearningPath path, LearningProfileSnapshot profile);
}
