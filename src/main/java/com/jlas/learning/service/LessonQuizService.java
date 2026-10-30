package com.jlas.learning.service;

import com.jlas.learning.dto.QuizAnswerFeedbackResponse;
import com.jlas.learning.dto.QuizAnswerSubmission;
import com.jlas.learning.dto.QuizIntegrityRequest;
import com.jlas.learning.dto.QuizIntegrityResponse;
import com.jlas.learning.dto.QuizResultResponse;
import com.jlas.learning.dto.QuizStartResponse;
import com.jlas.learning.dto.QuizSubmitRequest;
import com.jlas.learning.dto.QuizSubmitResponse;

/** Runs the lesson-quiz flow: open an attempt, submit answers, and review graded results. */
public interface LessonQuizService {

    /** Score in percent from which an attempt passes; passing a lesson's LESSON quiz completes the lesson. */
    int PASS_PERCENT = 60;

    /** Quizzes with a larger question bank show this many questions per attempt. */
    int QUESTIONS_PER_ATTEMPT = 20;

    /** Whole time allowed for a unit's final test (UNIT_FINAL), counted on the server from the attempt start. */
    int UNIT_FINAL_TIME_LIMIT_SECONDS = 10 * 60;

    /** Leaving a timed test this many times (tab, window, fullscreen, second tab, reload) closes the attempt. */
    int LEAVE_LIMIT = 3;

    /** Creates a fresh IN_PROGRESS attempt for the published quiz of a lesson and returns its questions. */
    QuizStartResponse start(Long userId, Long lessonId);

    /** Like {@link #start}, for the final test (UNIT_FINAL) of a unit. */
    QuizStartResponse startUnitFinal(Long userId, Long unitId);

    /**
     * Records an integrity event of an open timed attempt and returns how often the learner has left it.
     * Untimed attempts ignore the event.
     */
    QuizIntegrityResponse recordIntegrity(Long userId, Long attemptId, QuizIntegrityRequest event);

    /**
     * Grades one answer while the attempt is open and records it, so the learner sees right away whether
     * it was correct. Checking the same question again returns the recorded result unchanged.
     */
    QuizAnswerFeedbackResponse answer(Long userId, Long attemptId, QuizAnswerSubmission answer);

    /**
     * Grades the submitted answers, marks the attempt SUBMITTED, and returns the score. Questions already
     * answered through {@link #answer} keep their recorded result.
     */
    QuizSubmitResponse submit(Long userId, Long attemptId, QuizSubmitRequest request);

    /**
     * Drops an unfinished attempt (marks it ABANDONED, ungraded), so starting the quiz again opens a new
     * attempt instead of resuming it. An attempt that is no longer IN_PROGRESS is left as it is.
     */
    void abandon(Long userId, Long attemptId);

    /** Returns the graded result with per-answer detail, for a submitted attempt owned by the user. */
    QuizResultResponse result(Long userId, Long attemptId);
}
