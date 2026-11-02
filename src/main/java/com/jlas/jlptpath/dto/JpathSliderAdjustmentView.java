package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathSliderKind;
import java.time.LocalDateTime;

/**
 * [JLAS-W0108] One slider change of the path; {@code before} null for INITIAL; {@code counted} = it used one of the 2
 * adjustments of the level.
 */
public record JpathSliderAdjustmentView(String level, JpathSliderKind kind, boolean counted,
        JpathSliderValues before, JpathSliderValues after, int dailyMinutes, LocalDateTime createdAt) {
}
