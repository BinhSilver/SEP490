package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathPathStatus;

/**
 * [JLAS-W0108] Where {@code /learning/jlpt/start} sends a learner (path without the {@code redirect:} prefix), checked
 * in order: feature off → legacy path; no survey → survey; goal not JLPT → legacy path; ACTIVE path → /learning; PAUSED
 * → resume; no entry preference → survey day step; never studied → confirm FROM_ZERO; Smart Test attempt IN_PROGRESS or
 * SUBMITTED → confirm SMART_TEST with that attempt; otherwise → survey.
 */
public final class JpathStartRedirect {

    /** Facts the start route decides on; {@code studiedBefore}, {@code pathStatus} and the attempt may be null. */
    public record StartState(boolean enabled, boolean surveyDone, String goal, boolean preferenceDone,
                             Boolean studiedBefore, JpathPathStatus pathStatus, Long attemptId, String attemptStatus) {
    }

    private JpathStartRedirect() {
    }

    public static String resolve(StartState state) {
        if (!state.enabled()) {
            return "/learning/path";
        }
        if (!state.surveyDone()) {
            return "/survey";
        }
        if (!"JLPT".equals(state.goal())) {
            return "/learning/path";
        }
        if (state.pathStatus() == JpathPathStatus.ACTIVE) {
            return "/learning";
        }
        if (state.pathStatus() == JpathPathStatus.PAUSED) {
            return "/learning/jlpt/resume";
        }
        if (!state.preferenceDone()) {
            return "/survey?edit=days";
        }
        if (Boolean.FALSE.equals(state.studiedBefore())) {
            return "/learning/jlpt/confirm?source=FROM_ZERO";
        }
        if (state.attemptId() != null
                && ("IN_PROGRESS".equals(state.attemptStatus()) || "SUBMITTED".equals(state.attemptStatus()))) {
            return "/learning/jlpt/confirm?source=SMART_TEST&assessmentAttemptId=" + state.attemptId();
        }
        return "/survey";
    }
}
