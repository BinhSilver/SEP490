package com.jlas.learning.service;

import com.jlas.learning.dto.AttemptFeedbackResponse;
import com.jlas.learning.dto.MicroCheckDraftAnswer;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckSaveDraftRequest;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.dto.VocabularyStudyState;
import com.jlas.learning.dto.QuizAttemptHistoryResponse;

/** [JLAS-W0193] Vocabulary study (flip/remember) and its micro-check. The server owns state, eligibility
 * and score; the client never sends target IDs, scores or state transitions. */
public interface InteractiveVocabularyService {
    VocabularyStudyState studyState(Long userId, Long activityId);

    VocabularyStudyState flip(Long userId, Long activityId, Long memberId);

    VocabularyStudyState remember(Long userId, Long activityId, Long memberId, boolean remembered);

    MicroCheckResponse issueMicroCheck(Long userId, Long activityId);

    MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request);

    /** [JLAS-W0113] Autosaves in-progress selections for an issued attempt (restored on reopen/reload). */
    java.util.List<MicroCheckDraftAnswer> saveDraft(Long userId, Long activityId, MicroCheckSaveDraftRequest request);

    MicroCheckStateResponse microCheckState(Long userId, Long activityId);

    MicroCheckStateResponse completeReview(Long userId, Long activityId);
    /** [JLAS-W0108] Task 10 - REVIEW mode: read-only view of a finished activity. */
    VocabularyStudyState reviewState(Long userId, Long activityId);
    /** [JLAS-W0108] Task 10 - PRACTICE mode: non-credit replay of a finished activity. */
    MicroCheckResponse issuePractice(Long userId, Long activityId);
    MicroCheckResult submitPractice(Long userId, Long activityId, MicroCheckSubmitRequest request);
    /** [JLAS-W0108] REVIEW: stored feedback of the newest submitted attempt per batch (read-only). */
    java.util.List<AttemptFeedbackResponse> reviewFeedback(Long userId, Long activityId);
    /** All persisted scored and practice attempts for the quiz-results history bar. */
    java.util.List<QuizAttemptHistoryResponse> quizAttemptHistory(Long userId, Long activityId);
}
