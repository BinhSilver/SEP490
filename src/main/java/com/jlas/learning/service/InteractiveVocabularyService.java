package com.jlas.learning.service;

import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.dto.VocabularyStudyState;

/** [JLAS-W0193] Vocabulary study (flip/remember) and its micro-check. The server owns state, eligibility
 * and score; the client never sends target IDs, scores or state transitions. */
public interface InteractiveVocabularyService {
    VocabularyStudyState studyState(Long userId, Long activityId);

    VocabularyStudyState flip(Long userId, Long activityId, Long memberId);

    VocabularyStudyState remember(Long userId, Long activityId, Long memberId, boolean remembered);

    MicroCheckResponse issueMicroCheck(Long userId, Long activityId);

    MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request);

    MicroCheckStateResponse microCheckState(Long userId, Long activityId);

    MicroCheckStateResponse completeReview(Long userId, Long activityId);
}
