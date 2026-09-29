package com.jlas.shadowing.model;

import java.time.LocalDateTime;

/** Persisted comparison between one imported cue and the audio transcript. */
public record SubtitleQualityReview(
        Long subtitleId,
        SubtitleQualitySource source,
        String sourceText,
        String transcriptionText,
        double similarityScore,
        SubtitleQualityStatus status,
        String reason,
        Long reviewedByUserId,
        LocalDateTime reviewedAt) {
}
