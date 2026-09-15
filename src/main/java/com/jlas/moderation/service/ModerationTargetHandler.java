package com.jlas.moderation.service;

import com.jlas.moderation.dto.ModerationTargetSnapshot;

/**
 * Feature-owned adapter used by moderation to preview and resolve a target.
 * This keeps the moderation module independent from Flashcard and Shadowing entities.
 */
public interface ModerationTargetHandler {
    boolean supports(String targetType);

    ModerationTargetSnapshot snapshot(String targetType, Long targetId);

    void resolve(String targetType, Long targetId, boolean approve, String note);
}
