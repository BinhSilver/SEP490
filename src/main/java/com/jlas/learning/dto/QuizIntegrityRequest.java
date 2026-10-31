package com.jlas.learning.dto;

/**
 * One integrity event the test page saw: TAB_HIDDEN, WINDOW_BLUR, FULLSCREEN_EXIT, MULTI_TAB, COPY, PASTE,
 * CONTEXT_MENU, DEVTOOLS, TIME_UP or AUTO_SUBMIT, with an optional short detail.
 */
public record QuizIntegrityRequest(String type, String detail) {
}
