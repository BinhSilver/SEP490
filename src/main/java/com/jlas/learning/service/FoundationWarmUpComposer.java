package com.jlas.learning.service;

import com.jlas.learning.dto.InteractiveCompositionResult;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.entity.UserLearningPath;

/** [JLAS-W0108] Composes the Hiragana/Katakana foundation warm-up (a single-activity WARM_UP package
 * per kana chart lesson) for "from-zero" (BELOW_N5) learners only, before any other warm-up/target
 * package — design spec §2.2's sole exception to the four-part target package rule. A no-op (0 packages,
 * no gaps) for any learner who already knows the alphabet (assessedLevel != BELOW_N5). */
public interface FoundationWarmUpComposer {
    InteractiveCompositionResult composeFoundation(UserLearningPath path, LearningProfileSnapshot profile);
}
