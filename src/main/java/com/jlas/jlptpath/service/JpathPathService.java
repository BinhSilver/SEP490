package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathConfirmationView;
import com.jlas.jlptpath.dto.JpathEntryPreferenceRequest;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.dto.JpathEstimateRequest;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathEntrySource;
import java.util.Optional;

/** [JLAS-W0108] Entering the JLPT path and the learner's path. */
public interface JpathPathService {

    JpathEntryStatusView entryStatus(Long userId);

    /**
     * Saves the entry answers. A learner who never studied gets kana NONE; one who studied must pick REMEMBER or
     * REVIEW (VALIDATION_FAILED otherwise). Days must pass {@link com.jlas.jlptpath.util.JpathDays#validate}.
     */
    JpathEntryStatusView saveEntryPreference(Long userId, JpathEntryPreferenceRequest request);

    /**
     * Confirmation screen before creating a path; writes nothing. SMART_TEST opens at the Smart Test level capped to
     * the released levels (the given attempt, else the newest); an IN_PROGRESS attempt only sets
     * {@code smartTestInProgress}; a submitted attempt without a stored result counts as no Smart Test. FROM_ZERO and
     * ABANDONED_TEST open N5 without scores; ABANDONED_TEST accepts only the learner's own attempt (NOT_FOUND). An
     * attempt whose goal is not JLPT → VALIDATION_FAILED.
     */
    JpathConfirmationView confirmation(Long userId, JpathEntrySource source, Long assessmentAttemptId);

    /** What-if pace for the confirmation screen with the target enforced; writes nothing. */
    JpathPaceView estimate(Long userId, JpathEstimateRequest request);

    Optional<JpathUserPath> findActive(Long userId);

    /** The ACTIVE path; JPATH_NO_ACTIVE_PATH when there is none. */
    JpathUserPath requireActive(Long userId);
}
