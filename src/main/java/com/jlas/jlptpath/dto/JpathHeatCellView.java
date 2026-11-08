package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathDayKind;
import java.time.LocalDate;

/**
 * [JLAS-W0108] One heatmap day: study actions that day (rate, review, retype, skip, mark known), its shade 0–4 and
 * the day kind (null outside the path).
 */
public record JpathHeatCellView(LocalDate day, int count, int level, JpathDayKind kind) {
}
