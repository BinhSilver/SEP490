package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathStudyMode;
import jakarta.validation.constraints.NotNull;

/** [JLAS-W0108] Switch between studying combined (Gộp) and split (Tách). */
public record JpathModeRequest(@NotNull JpathStudyMode mode) {
}
