package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] Verdict of the comprehension check. The explanation is shown right or wrong (#36: a wrong pick shows it
 * and the learner may pick again without limit); nothing is scored.
 */
public record JpathSkillLessonCheckResult(boolean correct, String explanationVi) {
}
