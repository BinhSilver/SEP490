package com.jlas.moderation.dto;

import java.time.LocalDateTime;

/** Read model returned to the moderator queue UI. */
public record ModerationQueueItem(
        Long id,
        String targetType,
        Long targetId,
        Long ownerUserId,
        String titleSnapshot,
        String summary,
        String status,
        String contentPreview,
        String categoriesJson,
        Double confidence,
        String modelName,
        String source,
        String targetUrl,
        LocalDateTime createdAt) {
}
