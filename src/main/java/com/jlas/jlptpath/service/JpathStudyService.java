package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathEventBatchResult;
import com.jlas.jlptpath.dto.JpathSessionStartRequest;
import com.jlas.jlptpath.dto.JpathSessionView;
import com.jlas.jlptpath.dto.JpathStudyEventBatch;
import com.jlas.jlptpath.enums.JpathTab;
import java.util.Optional;

/**
 * [JLAS-W0108] Study sessions of the JLPT path: start or resume, the open session of today, one session by id. A
 * session belongs to its learner only: another learner's id reads as not found ({@code JPATH_SESSION_NOT_FOUND}).
 */
public interface JpathStudyService {

    /**
     * TODAY: the OPEN TODAY session of today's plan on the same tab is returned (a {@code startItemCode} still after
     * the position moves to the current step); otherwise a new one is built from today's plan. EXTRA: the tab of today
     * must be done ({@code JPATH_EXTRA_NOT_READY}). LOWER_LEVEL: a level below the open one ({@code JPATH_LEVEL_LOCKED}
     * above it). BROWSE: new items of a topic, a grammar unit or one item ({@code JPATH_CONTENT_NOT_LEARNABLE} when it
     * cannot be taught); a kana row {@code HR-…} queues its kana and a KANA_ROW check. NOTEBOOK: the notebook words
     * and kanji (none → COMPLETED at once). No ACTIVE path → {@code JPATH_NO_ACTIVE_PATH}.
     */
    JpathSessionView start(Long userId, JpathSessionStartRequest request);

    /**
     * The newest OPEN TODAY session of today's plan on {@code tab} or COMBINED ({@code tab} null = any tab); empty
     * without a plan today or such a session.
     */
    Optional<JpathSessionView> current(Long userId, JpathTab tab);

    /** The learner's session {@code sessionId}; another learner's or unknown → {@code JPATH_SESSION_NOT_FOUND}. */
    JpathSessionView get(Long userId, Long sessionId);

    /**
     * Applies the card buttons of the learner's session {@code sessionId} in one transaction, each event once by its
     * client event id (a replayed offline batch gives DUPLICATE outcomes and changes nothing). Events of a step that
     * is not the current one, or of a closed session, are REJECTED. Another learner's or unknown session →
     * {@code JPATH_SESSION_NOT_FOUND} with nothing written.
     */
    JpathEventBatchResult applyEvents(Long userId, Long sessionId, JpathStudyEventBatch batch);
}
