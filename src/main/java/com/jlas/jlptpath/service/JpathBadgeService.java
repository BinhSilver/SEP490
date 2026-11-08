package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathBadgeView;
import java.util.List;

/** [JLAS-W0108] Badges of the JLPT path: the catalog with the learner's progress, and granting new badges. */
public interface JpathBadgeService {

    /** Every badge in catalog order, with earned state and progress (capped at the goal). */
    List<JpathBadgeView> badges(Long userId);

    /**
     * Grants every badge whose rule now holds and that the learner does not have yet; each new badge queues its CP
     * ({@code JPATH_BADGE}, ref = ordinal) in the current transaction. Returns the codes granted by this call.
     */
    List<String> evaluate(Long userId);
}
