package com.jlas.learning.service;

import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningPathResponse;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.LessonQuizSubmitRequest;

/** Orchestrates two-phase learning path creation and reading. */
public interface LearningService {
    /** Creates a path for a finalized assessment attempt, or returns the existing one on retry. */
    CreateLearningPathResult create(Long userId, Long assessmentAttemptId);

    /**
     * [JLAS-W0108] Creates a from-zero path from a completed survey (no Smart Test needed), or
     * returns the existing one on retry. The path warms up with the kana foundation before the goal.
     */
    CreateLearningPathResult createFromSurvey(Long userId);

    /** Returns the user's active path; empty state is NOT_FOUND. */
    LearningPathResponse current(Long userId);

    /** Marks a lesson COMPLETED for the user, creating the progress record if needed. */
    void completeLesson(Long userId, Long lessonId);

    /** [JLAS-W0113] Returns the questions for a lesson quiz (answers withheld). */
    LessonQuizResponse getLessonQuiz(Long userId, Long lessonId);

    /** [JLAS-W0113] Grades a submitted lesson quiz against the answer key; on pass marks the lesson complete. */
    LessonQuizResult submitLessonQuiz(Long userId, Long lessonId, LessonQuizSubmitRequest request);

    /** [JLAS-W0113] Returns the warm-up gate quiz: questions aggregated from the weak tags of the WARM_UP phase. */
    LessonQuizResponse getGateQuiz(Long userId);

    /** [JLAS-W0113] Grades the gate quiz; on pass unlocks the GOAL_LEARNING phase (records gate_passed_at). */
    LessonQuizResult submitGateQuiz(Long userId, LessonQuizSubmitRequest request);
}
