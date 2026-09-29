package com.jlas.learning.service;

import com.jlas.learning.dto.FoundationStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;

/** [JLAS-W0108] Hiragana/Katakana foundation study (flip/remember) and its micro-check. Same server-owned
 * state/eligibility/score contract as {@link InteractiveVocabularyService}/{@link InteractiveKanjiService};
 * this is a thin content adapter over the shared engine, not a second state machine. */
public interface InteractiveFoundationService {
    FoundationStudyState studyState(Long userId, Long activityId);

    FoundationStudyState flip(Long userId, Long activityId, Long memberId);

    FoundationStudyState remember(Long userId, Long activityId, Long memberId, boolean remembered);

    MicroCheckResponse issueMicroCheck(Long userId, Long activityId);

    MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request);

    MicroCheckStateResponse microCheckState(Long userId, Long activityId);

    MicroCheckStateResponse completeReview(Long userId, Long activityId);
}
