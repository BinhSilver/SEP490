package com.jlas.learning.service;

import com.jlas.learning.dto.FinalEligibilityResponse;

public interface LearningFinalEligibilityService {
    /** Eligibility for the caller's ACTIVE interactive path; 404 when there is none. */
    FinalEligibilityResponse forUser(Long userId);
}
