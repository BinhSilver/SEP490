package com.jlas.learning.service;

import com.jlas.learning.dto.KanjiStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;

/** [JLAS-W0193] Kanji study (flip/remember) and its micro-check. Same server-owned state/eligibility/score
 * contract as {@link InteractiveVocabularyService} (Task 4); Task 5 extracted the shared engine so this is a
 * thin content adapter, not a second state machine. */
public interface InteractiveKanjiService {
    KanjiStudyState studyState(Long userId, Long activityId);

    KanjiStudyState flip(Long userId, Long activityId, Long memberId);

    KanjiStudyState remember(Long userId, Long activityId, Long memberId, boolean remembered);

    MicroCheckResponse issueMicroCheck(Long userId, Long activityId);

    MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request);

    MicroCheckStateResponse microCheckState(Long userId, Long activityId);

    MicroCheckStateResponse completeReview(Long userId, Long activityId);
    /** [JLAS-W0108] Task 10 - REVIEW mode: read-only view of a finished activity. */
    KanjiStudyState reviewState(Long userId, Long activityId);
    /** [JLAS-W0108] Task 10 - PRACTICE mode: non-credit replay of a finished activity. */
    MicroCheckResponse issuePractice(Long userId, Long activityId);
    MicroCheckResult submitPractice(Long userId, Long activityId, MicroCheckSubmitRequest request);
}
