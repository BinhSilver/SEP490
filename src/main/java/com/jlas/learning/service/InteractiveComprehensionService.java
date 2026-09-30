package com.jlas.learning.service;

import com.jlas.learning.dto.*;

public interface InteractiveComprehensionService {
    ComprehensionStudyState studyState(Long userId, Long activityId);
    MicroCheckResponse issueMicroCheck(Long userId, Long activityId);
    MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request);
    MicroCheckStateResponse microCheckState(Long userId, Long activityId);
    MicroCheckStateResponse recordReviewEvidence(Long userId, Long activityId);
    MicroCheckStateResponse completeReview(Long userId, Long activityId);
    /** [JLAS-W0108] Task 10 - PRACTICE mode: non-credit replay of a finished activity. */
    ComprehensionReplayResponse issuePractice(Long userId, Long activityId);
    MicroCheckResult submitPractice(Long userId, Long activityId, MicroCheckSubmitRequest request);
    /** [JLAS-W0108] REVIEW: stored feedback of the newest submitted attempt per batch (read-only). */
    java.util.List<ComprehensionAttemptFeedback> reviewFeedback(Long userId, Long activityId);
}
