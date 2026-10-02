package com.jlas.learning.service;

import com.jlas.learning.dto.DialogueHeartbeatRequest;
import com.jlas.learning.dto.DialogueProgressResult;
import com.jlas.learning.dto.DialogueStudyState;

/** [JLAS-W0193] Task 7 — Dialogue: read-only canonical script plus server-timed, evidence-based completion.
 * Unlike Vocabulary/Kanji/Grammar, Dialogue has no micro-check and does not use
 * {@code InteractiveStudyEngine} — there is no score, no pass/fail, no attempt cycle. Completion instead
 * requires both server-computed accumulated active-visible time (>= 180s) and persisted bottom-reached
 * evidence, neither of which the client can set directly. */
public interface InteractiveDialogueService {
    DialogueStudyState studyState(Long userId, Long activityId);

    DialogueProgressResult heartbeat(Long userId, Long activityId, DialogueHeartbeatRequest request);

    DialogueProgressResult reachedBottom(Long userId, Long activityId);
}
