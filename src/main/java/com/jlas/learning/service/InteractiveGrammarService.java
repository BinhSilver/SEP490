package com.jlas.learning.service;

import com.jlas.learning.dto.AttemptFeedbackResponse;
import com.jlas.learning.dto.GrammarFillSubmitRequest;
import com.jlas.learning.dto.GrammarOrderingSubmitRequest;
import com.jlas.learning.dto.GrammarPracticeResult;
import com.jlas.learning.dto.GrammarStudyState;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;

/** [JLAS-W0193] Task 6 — Grammar: 1-3 approved points, each requiring ordering + fill-blank practice before
 * its micro-check is eligible. The practice gate is Grammar-specific (no flip/remember card); the
 * micro-check itself reuses the exact Vocabulary/Kanji contract via {@code InteractiveStudyEngine}. */
public interface InteractiveGrammarService {
    GrammarStudyState studyState(Long userId, Long activityId);

    /** Read-only canonical content for a completed grammar activity. */
    GrammarStudyState reviewState(Long userId, Long activityId);

    GrammarPracticeResult submitOrdering(Long userId, Long activityId, Long grammarPointId,
            GrammarOrderingSubmitRequest request);

    GrammarPracticeResult submitFill(Long userId, Long activityId, Long grammarPointId,
            GrammarFillSubmitRequest request);

    MicroCheckResponse issueMicroCheck(Long userId, Long activityId);

    MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request);

    MicroCheckStateResponse microCheckState(Long userId, Long activityId);

    MicroCheckStateResponse completeReview(Long userId, Long activityId);
    /** [JLAS-W0108] Task 10 - PRACTICE mode: non-credit replay of a finished activity. */
    MicroCheckResponse issuePractice(Long userId, Long activityId);
    MicroCheckResult submitPractice(Long userId, Long activityId, MicroCheckSubmitRequest request);
    /** [JLAS-W0108] REVIEW: stored feedback of the newest submitted attempt per batch (read-only). */
    java.util.List<AttemptFeedbackResponse> reviewFeedback(Long userId, Long activityId);
}
