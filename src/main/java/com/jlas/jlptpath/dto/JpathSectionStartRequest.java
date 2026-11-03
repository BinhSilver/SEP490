package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathSkill;
import jakarta.validation.constraints.NotNull;

/** [JLAS-W0108] {@code POST /api/learning/jlpt/skills/sections}: the skill of the timed section practice. */
public record JpathSectionStartRequest(@NotNull JpathSkill skill) {
}
