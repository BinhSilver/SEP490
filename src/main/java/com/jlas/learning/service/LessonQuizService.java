package com.jlas.learning.service;

import com.jlas.learning.dto.QuizStartResponse;
import com.jlas.learning.dto.QuizSubmitRequest;
import com.jlas.learning.dto.QuizSubmitResponse;

/** Runs the lesson-quiz flow: open an attempt and submit answers for grading. */
public interface LessonQuizService {

    /** Creates a fresh IN_PROGRESS attempt for the published quiz of a lesson and returns its questions. */
    QuizStartResponse start(Long userId, Long lessonId);

    /** Grades all submitted answers, marks the attempt SUBMITTED, and returns the score. */
    QuizSubmitResponse submit(Long userId, Long attemptId, QuizSubmitRequest request);
}
