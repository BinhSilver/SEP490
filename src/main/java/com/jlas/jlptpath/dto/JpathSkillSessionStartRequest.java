package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathSkill;
import com.jlas.jlptpath.enums.JpathSkillSessionKind;
import jakarta.validation.constraints.NotNull;

/**
 * [JLAS-W0108] Start (or resume) a skill session: DAY ignores {@code typeCode} (the type of the day, #37); FREE needs
 * it. SECTION sessions are started elsewhere.
 */
public record JpathSkillSessionStartRequest(@NotNull JpathSkill skill, @NotNull JpathSkillSessionKind kind,
                                            String typeCode) {
}
