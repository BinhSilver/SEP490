package com.jlas.learning.service;

import com.jlas.learning.dto.*;

public interface InteractiveComprehensionService {
    ComprehensionStudyState studyState(Long userId, Long activityId);
    MicroCheckResponse issueMicroCheck(Long userId, Long activityId);
    MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request);
    MicroCheckStateResponse microCheckState(Long userId, Long activityId);
    MicroCheckStateResponse recordReviewEvidence(Long userId, Long activityId);
    MicroCheckStateResponse completeReview(Long userId, Long activityId);
}
