package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathConfirmationView;
import com.jlas.jlptpath.dto.JpathCreateRequest;
import com.jlas.jlptpath.dto.JpathEntryPreferenceRequest;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.dto.JpathEstimateRequest;
import com.jlas.jlptpath.dto.JpathLevelPreviewView;
import com.jlas.jlptpath.dto.JpathPaceView;
import com.jlas.jlptpath.dto.JpathPathView;
import com.jlas.jlptpath.dto.JpathSliderUpdateRequest;
import com.jlas.jlptpath.dto.JpathSliderView;
import com.jlas.jlptpath.entity.JpathUserLevel;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathEntrySource;
import com.jlas.jlptpath.enums.JpathStudyMode;
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

    /**
     * Creates the learner's path from the confirmation screen; checks everything before writing. Rejects: an ACTIVE
     * or PAUSED path (JPATH_ACTIVE_PATH_EXISTS, also when a parallel request inserted first); a survey goal that is no
     * longer JLPT (VALIDATION_FAILED); SMART_TEST without a submitted attempt of the learner (IN_PROGRESS →
     * JPATH_SMART_TEST_IN_PROGRESS); FROM_ZERO for a learner who studied before; bad minutes or days; sliders outside
     * the limits of the open level; an exam date not in (today, today + 3 years]; a target that cannot be reached
     * before the exam (JPATH_TARGET_TOO_SOON with the estimate message). The open level, kana tab and suggested mode
     * are decided here again, never taken from the client. Writes the path, its open level, the INITIAL (and, when
     * the sliders differ from the recommendation, CONFIRM) slider log, default display settings; imports the wrong
     * Smart Test items (SMART_TEST with a result) or finishes the abandoned IN_PROGRESS attempt (ABANDONED_TEST).
     */
    JpathPathView create(Long userId, JpathCreateRequest request);

    /** The active path view; JPATH_NO_ACTIVE_PATH when there is none. */
    JpathPathView current(Long userId);

    /** The open level row of the active path; JPATH_NO_ACTIVE_PATH when there is none. */
    JpathUserLevel openLevelState(Long userId);

    /**
     * Preview of {@code level} (N1..N5, any case; VALIDATION_FAILED otherwise) as seen from the active path's open
     * level (N5 without a path).
     */
    JpathLevelPreviewView levelPreview(Long userId, String level);

    /**
     * Sets the open level's sliders (policy #29): out of the level's limits → JPATH_SLIDER_OUT_OF_RANGE; the same
     * values change nothing; the first drag after creating the path is free; later changes use one of the 2
     * adjustments of the level (none left → JPATH_ADJUSTMENTS_EXHAUSTED). Every change is logged and refreshes today's
     * targets of the tabs not started yet; {@code appliesFromTomorrow} lists the started ones.
     */
    JpathSliderView updateSliders(Long userId, JpathSliderUpdateRequest request);

    /** Rebalance: sliders back to the recommendation for the daily minutes; logged, never counted. */
    JpathPaceView rebalance(Long userId);

    /** Switches between Gộp and Tách (null → VALIDATION_FAILED); sliders and adjustments stay. */
    JpathPathView changeMode(Long userId, JpathStudyMode mode);

    /** Recomputes the pace of the active path and stores its priority mode (#30); no active path → nothing. */
    void refreshPacing(Long userId);

    Optional<JpathUserPath> findActive(Long userId);

    /** The ACTIVE path; JPATH_NO_ACTIVE_PATH when there is none. */
    JpathUserPath requireActive(Long userId);

    /**
     * Pauses the ACTIVE path (none → JPATH_NO_ACTIVE_PATH) while the learner changes the survey goal; nothing else
     * changes. A paused path blocks creating a new one; the JLPT start page resumes it.
     */
    void pauseForGoalChange(Long userId);

    /** Makes the PAUSED path ACTIVE again and returns its view; no PAUSED path → JPATH_NO_ACTIVE_PATH. */
    JpathPathView resumePaused(Long userId);
}
