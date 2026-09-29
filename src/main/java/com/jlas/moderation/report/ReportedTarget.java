package com.jlas.moderation.report;

import java.time.LocalDateTime;
import java.util.List;

/** One reported piece of community content, grouped with all of its open reports. */
public record ReportedTarget(String targetType, Long targetId, String state,
                             Long ownerUserId, String ownerName,
                             String preview, String targetUrl,
                             int reportCount, List<String> reasons, LocalDateTime hiddenAt) {
}
