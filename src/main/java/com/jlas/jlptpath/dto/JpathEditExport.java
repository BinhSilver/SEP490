package com.jlas.jlptpath.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [JLAS-W0108] Unexported admin edits of a level, one row per (target, column) with its last value, without
 * report_hidden; sorted by target type, target code and column. Saved as {@code data/learning-path/edits/<level>.json}
 * for the seed generator, which reads {@code edits[]}.
 */
public record JpathEditExport(String level, LocalDateTime exportedAt, int count, List<JpathEditExportRow> edits) {
}
