package com.jlas.learning.service;

import com.jlas.learning.dto.AttemptFeedbackResponse;
import com.jlas.learning.dto.FoundationStudyState;
import com.jlas.learning.dto.MicroCheckDraftAnswer;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckSaveDraftRequest;
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

    /** [JLAS-W0113] Autosaves in-progress selections for an issued attempt (restored on reopen/reload). */
    java.util.List<MicroCheckDraftAnswer> saveDraft(Long userId, Long activityId, MicroCheckSaveDraftRequest request);

    MicroCheckStateResponse microCheckState(Long userId, Long activityId);

    MicroCheckStateResponse completeReview(Long userId, Long activityId);
    /** [JLAS-W0108] Task 10 - REVIEW mode: read-only view of a finished activity. */
    FoundationStudyState reviewState(Long userId, Long activityId);
    /** [JLAS-W0108] Task 10 - PRACTICE mode: non-credit replay of a finished activity. */
    MicroCheckResponse issuePractice(Long userId, Long activityId);
    MicroCheckResult submitPractice(Long userId, Long activityId, MicroCheckSubmitRequest request);
    /** [JLAS-W0108] REVIEW: stored feedback of the newest submitted attempt per batch (read-only). */
    java.util.List<AttemptFeedbackResponse> reviewFeedback(Long userId, Long activityId);
    /** All submitted quiz and non-credit practice attempts for this learner's activity. */
    java.util.List<com.jlas.learning.dto.QuizAttemptHistoryResponse> quizAttemptHistory(Long userId, Long activityId);
}
