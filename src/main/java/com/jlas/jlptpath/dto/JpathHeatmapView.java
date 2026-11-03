package com.jlas.jlptpath.dto;

import java.time.LocalDate;
import java.util.List;

/** [JLAS-W0108] Heatmap of {@code [from, to]}: one cell per day, oldest first. */
public record JpathHeatmapView(LocalDate from, LocalDate to, List<JpathHeatCellView> cells) {
}
