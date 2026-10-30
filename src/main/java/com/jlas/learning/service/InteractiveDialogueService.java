package com.jlas.learning.service;

import com.jlas.learning.dto.DialogueHeartbeatRequest;
import com.jlas.learning.dto.DialogueProgressResult;
import com.jlas.learning.dto.DialogueStudyState;

import java.util.List;

/** [JLAS-W0193] Task 7 — Dialogue: read-only canonical script plus server-timed, evidence-based completion.
 * Unlike Vocabulary/Kanji/Grammar, Dialogue has no micro-check and does not use
 * {@code InteractiveStudyEngine} — there is no score, no pass/fail, no attempt cycle. Completion instead
 * requires both server-computed accumulated active-visible time (>= 180s) and persisted bottom-reached
 * evidence, neither of which the client can set directly. */
public interface InteractiveDialogueService {
    DialogueStudyState studyState(Long userId, Long activityId);

    /** Read-only replay of a completed dialogue; neither endpoint records learning progress. */
    DialogueStudyState reviewState(Long userId, Long activityId);
    DialogueStudyState practiceState(Long userId, Long activityId);

    DialogueProgressResult heartbeat(Long userId, Long activityId, DialogueHeartbeatRequest request);

    DialogueProgressResult reachedBottom(Long userId, Long activityId);

    /** Owner 2026-10-19: communication dialogues complete when every line was practised and the end reached. */
    DialogueProgressResult practiceLine(Long userId, Long activityId, Long lineId);

    /** §2.24: flips one "Từ & cụm từ" card of a communication dialogue; idempotent. */
    DialogueProgressResult flipPhrase(Long userId, Long activityId, int phraseIndex);

    /** §2.24 G1: the optional reply challenge, without its answer key. */
    com.jlas.learning.dto.DialogueChallenge.View challenge(Long userId, Long activityId);

    /** Checks one choice; writes nothing. */
    com.jlas.learning.dto.DialogueChallenge.Answer answerChallenge(Long userId, Long activityId, int turn, String optionId);

    /** Grades a whole run; the first full clear records the clear and awards 2 CP once (G3). */
    com.jlas.learning.dto.DialogueChallenge.Result completeChallenge(Long userId, Long activityId, List<String> answers);
}
