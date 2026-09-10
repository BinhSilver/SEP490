package com.jlas.moderation.service;

import com.jlas.moderation.dto.ModerationResult;
import java.nio.file.Path;
import java.util.List;

/** Boundary used by feature modules that need content moderation. */
public interface ContentModerationPort {
    ModerationResult scanText(String text);
    ModerationResult scanCombined(List<String> parts);
    ModerationResult scanImage(Path imagePath, String originalFilename);
    ModerationResult mergeVerdicts(List<ModerationResult> results);
    boolean isVisionConfigured();
    void checkComment(String key);
    void checkPublish(String key);
    Long persistLog(String targetType, Long targetId, Long ownerUserId,
                    String source, String rawText, ModerationResult result);
    void enqueueReview(Long moderationLogId, String targetType, Long targetId,
                       Long ownerUserId, String titleSnapshot, String summary);
}
