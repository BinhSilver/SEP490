package com.jlas.learning.service;

import com.jlas.learning.dto.QuizResultResponse;
import com.jlas.learning.dto.QuizStartResponse;
import com.jlas.learning.dto.QuizSubmitRequest;
import com.jlas.learning.dto.QuizSubmitResponse;

/** Runs the lesson-quiz flow: open an attempt, submit answers, and review graded results. */
public interface LessonQuizService {

    /** Creates a fresh IN_PROGRESS attempt for the published quiz of a lesson and returns its questions. */
    QuizStartResponse start(Long userId, Long lessonId);

    /** Grades all submitted answers, marks the attempt SUBMITTED, and returns the score. */
    QuizSubmitResponse submit(Long userId, Long attemptId, QuizSubmitRequest request);

    /** Returns the graded result with per-answer detail, for a submitted attempt owned by the user. */
    QuizResultResponse result(Long userId, Long attemptId);
}
