package com.jlas.moderation.service;

import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.repository.ContentModerationLogRepository;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Keeps Shadowing coupled to the moderation port instead of concrete services. */
@Service
@RequiredArgsConstructor
public class ShadowingModerationAdapter implements ContentModerationPort {
    private final ContentModerationService contentModerationService;
    private final OpenRouterVisionClient visionClient;
    private final ModerationRateLimitService rateLimitService;
    private final ContentModerationLogRepository logRepository;

    @Override public ModerationResult scanText(String text) {
        return contentModerationService.scanText(text);
    }

    @Override public ModerationResult scanCombined(List<String> parts) {
        return contentModerationService.scanCombined(parts);
    }

    @Override public ModerationResult scanImage(Path imagePath, String originalFilename) {
        return contentModerationService.scanImage(imagePath, originalFilename);
    }

    @Override public ModerationResult mergeVerdicts(List<ModerationResult> results) {
        return contentModerationService.mergeVerdicts(results);
    }

    @Override public boolean isVisionConfigured() { return visionClient.isConfigured(); }

    @Override public void checkComment(String key) { rateLimitService.checkComment(key); }
    @Override public void checkPublish(String key) { rateLimitService.checkPublish(key); }

    @Override
    public Long persistLog(String targetType, Long targetId, Long ownerUserId,
                           String source, String rawText, ModerationResult result) {
        return contentModerationService.persistLog(targetType, targetId, ownerUserId,
                source, rawText, result).getId();
    }

    @Override
    public void enqueueReview(Long moderationLogId, String targetType, Long targetId,
                              Long ownerUserId, String titleSnapshot, String summary) {
        contentModerationService.enqueueReview(
                moderationLogId != null ? logRepository.findById(moderationLogId).orElse(null) : null,
                targetType, targetId, ownerUserId, titleSnapshot, summary);
    }
}
