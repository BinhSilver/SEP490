package com.jlas.jlptpath.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [JLAS-W0108] Every admin edit of a level (cumulative), one row per (target, column) with its last value, without
 * report_hidden; sorted by target type, target code and column. It replaces {@code data/learning-path/edits/<level>.json}
 * as a whole (the seed generator reads {@code edits[]}), so re-exporting after a lost download is always safe.
 * {@code count} = rows in {@code edits}; {@code newCount} = edit log rows first stamped exported by this call.
 */
public record JpathEditExport(String level, LocalDateTime exportedAt, int count, int newCount,
                              List<JpathEditExportRow> edits) {
}
