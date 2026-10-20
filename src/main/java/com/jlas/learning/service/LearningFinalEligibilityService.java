package com.jlas.learning.service;

import com.jlas.learning.dto.FinalEligibilityResponse;

public interface LearningFinalEligibilityService {
    /** Eligibility for the caller's ACTIVE interactive path; 404 when there is none. */
    FinalEligibilityResponse forUser(Long userId);

    /** Same decision for an explicit path id (used by completion while it holds the path lock). */
    FinalEligibilityResponse forPath(Long pathId);
}
