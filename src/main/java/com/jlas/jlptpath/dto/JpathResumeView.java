package com.jlas.jlptpath.dto;

/** [JLAS-W0108] The open TODAY session of today's plan, to continue where the learner stopped. */
public record JpathResumeView(Long sessionId, int position, int total) {
}
