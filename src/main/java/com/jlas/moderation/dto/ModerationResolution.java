package com.jlas.moderation.dto;

/** Result returned after a moderator resolves one queue item. */
public record ModerationResolution(
        Long id,
        String status,
        String targetType,
        Long targetId,
        Integer creditReward,
        Long creditedToUserId) {
}
