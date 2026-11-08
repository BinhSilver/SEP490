package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotBlank;

/** [JLAS-W0108] Answer to a lesson's comprehension check: option id o1..oN, and the session it belongs to (optional). */
public record JpathSkillLessonCheckRequest(@NotBlank String option, Long sessionId) {
}
