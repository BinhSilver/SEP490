package com.jlas.moderation.dto;

import java.time.LocalDateTime;

public record AppealView(Long id, Long requesterUserId, Long sanctionId, String sanctionType,
                         String reason, String evidenceUrl, String status,
                         Long reviewerId, String resolutionNote,
                         LocalDateTime createdAt, LocalDateTime resolvedAt, EvidenceFile evidenceFile) {
    public record EvidenceFile(String name, String contentType, Long size, String downloadUrl) {}
}
