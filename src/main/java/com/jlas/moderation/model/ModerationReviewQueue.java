package com.jlas.moderation.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "review_case", indexes = {
        @Index(name = "idx_mod_queue_status", columnList = "status,created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModerationReviewQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_type", nullable = false, length = 30)
    @Builder.Default
    private String caseType = "CONTENT_REVIEW";

    @Column(name = "moderation_log_id")
    private Long logId;

    @Column(name = "target_type", nullable = false, length = 40)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "requester_user_id")
    private Long requesterUserId;

    @Column(name = "title_snapshot", length = 255)
    private String titleSnapshot;

    @Column(name = "reason", length = 1000)
    private String summary;

    /** OPEN | APPROVED | REJECTED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Transient
    private String note;

    @Transient
    private String appealNote;

    @Transient
    private LocalDateTime appealedAt;

    /** Context for the moderation admin UI; these values are not persisted. */
    @Transient
    private String contentPreview;

    @Transient
    private String categoriesJson;

    @Transient
    private Double confidence;

    @Transient
    private String modelName;

    @Transient
    private String source;

    @Transient
    private String targetUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "OPEN";
        }
    }
}
