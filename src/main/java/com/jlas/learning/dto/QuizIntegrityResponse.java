package com.jlas.learning.dto;

/**
 * Where the attempt stands after an integrity event.
 *
 * @param leaveCount times the learner has left the test so far
 * @param leaveLimit leaving this many times closes the attempt
 * @param locked     true once the limit is reached: no more answers are accepted, the page submits
 */
public record QuizIntegrityResponse(int leaveCount, int leaveLimit, boolean locked) {
}
