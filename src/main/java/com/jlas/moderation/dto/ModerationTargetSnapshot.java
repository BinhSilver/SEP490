package com.jlas.moderation.dto;

/** Optional target context added to a moderation queue row. */
public record ModerationTargetSnapshot(String contentPreview, String targetUrl) {
    public static ModerationTargetSnapshot empty() {
        return new ModerationTargetSnapshot(null, null);
    }
}
