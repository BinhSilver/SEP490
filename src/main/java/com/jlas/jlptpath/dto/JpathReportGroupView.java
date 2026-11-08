package com.jlas.jlptpath.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] One reported target in the editor queue: every report of the filter's status on it, grouped.
 * {@code errorTypes} counts reports per error type, {@code notes} holds the 5 newest non-empty notes (newest first).
 * A HOLD target stays in the queue with the preview "(mục đã ngừng hiển thị)"; {@code contentStatus} is null for
 * kana and for a row that no longer exists.
 */
public record JpathReportGroupView(String targetType, String targetCode, String itemType, String itemCode,
                                   String level, String preview, long reportCount, long reporterCount,
                                   Map<String, Long> errorTypes, List<String> notes, LocalDateTime firstAt,
                                   LocalDateTime lastAt, boolean hidden, String contentStatus) {
}
