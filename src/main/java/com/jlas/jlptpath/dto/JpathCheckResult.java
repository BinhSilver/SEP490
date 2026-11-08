package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] Result of a submitted check (stored, so a second submit returns the same result). {@code passed} is
 * null for a group exercise; {@code tooFast} still counts progress but pays no CP.
 */
public record JpathCheckResult(String token, int correct, int total, int scorePercent, Boolean passed,
                               boolean rewardEligible, boolean tooFast, List<JpathWrongItemView> wrongItems,
                               int cpAwarded, JpathCheckNext next) {
}
