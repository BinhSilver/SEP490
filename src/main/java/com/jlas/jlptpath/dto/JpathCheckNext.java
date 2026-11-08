package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathTab;

/**
 * [JLAS-W0108] What the page offers after a submit. {@code action}: CONTINUE (next step of the session), RETRY (failed,
 * the same step again), DONE (session finished) or BACK (no session). {@code url}: the study page, the done page or
 * null.
 */
public record JpathCheckNext(String action, String label, String url, JpathTab nextTab, Integer nextCount,
                             Long sessionId) {
}
