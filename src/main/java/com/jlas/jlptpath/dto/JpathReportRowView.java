package com.jlas.jlptpath.dto;

import java.time.LocalDateTime;

/** [JLAS-W0108] One learner report on a target, as the editor sees it (no reporter identity). */
public record JpathReportRowView(Long id, String errorType, String note, String pageContext, String status,
                                 LocalDateTime createdAt, LocalDateTime resolvedAt) {
}
