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

    /**
     * Applies a revised decision to a target that was already resolved once.
     * Handlers whose first resolve guards against a finished state override this.
     */
    default void revise(String targetType, Long targetId, boolean approve, String note) {
        resolve(targetType, targetId, approve, note);
    }
}
