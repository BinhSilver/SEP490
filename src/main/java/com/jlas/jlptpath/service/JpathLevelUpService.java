package com.jlas.jlptpath.service;

import com.jlas.assessment.dto.JlptMockResultResponse;
import com.jlas.jlptpath.dto.JpathLevelSummary;
import com.jlas.jlptpath.entity.JpathMockResult;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * [JLAS-W0108] Level-up by the JLPT mock test (Plan 3 Task 18, policy #42, #44, #45), the level summary and the
 * certificate gate.
 */
public interface JpathLevelUpService {

    /**
     * Applies an attempt the caller ({@code JpathMockService}) already checked (own, open level, after opened_at).
     * Not called inside a transaction: runs {@code ensurePlanExists} first, then one writer transaction with the lock
     * order path → level → plan. Records jpath_mock_result once per attempt (a repeat returns the stored row and does
     * nothing else); a mock submitted today marks today done + streak once (no day CP); PASSED marks the level PASSED,
     * pays +20 CP and the MOCK_PASS_L badge, and opens the next released level unless the target is reached.
     */
    JpathMockResult apply(Long userId, JlptMockResultResponse result);

    /** Summary of {@code level} in the ACTIVE path; a level outside the path → NOT_FOUND. */
    JpathLevelSummary summary(Long userId, String level);

    /** When the level was passed — present only when its stage is PASSED (certificate gate). */
    Optional<LocalDateTime> passedAt(Long userId, String level);
}
