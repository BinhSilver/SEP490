package com.jlas.moderation.report;

import java.time.LocalDateTime;

/** Decision passed to the feature that owns the content, so it can store it on its reports. */
public record ReportDecision(boolean valid, String newState, Long moderatorId, String reason,
                             LocalDateTime decidedAt) {
}
