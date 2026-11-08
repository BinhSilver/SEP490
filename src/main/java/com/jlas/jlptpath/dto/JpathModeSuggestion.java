package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathStudyMode;

/**
 * [JLAS-W0108] Suggested combined/split study (policy #27): {@code reason} is NO_SMART_TEST, LOW_MINUTES, EVEN_SKILLS or
 * WEAK_SKILL (stored in jpath_user_path.suggestion_reason); {@code reasonVi} is the sentence shown to the learner.
 */
public record JpathModeSuggestion(JpathStudyMode mode, String reason, String reasonVi) {
}
