package com.jlas.moderation.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Case review/appeal moderation dùng chung.
 * Table: review_case
 */
@Entity
@Table(name = "review_case")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_type", nullable = false, length = 30)
    private String caseType;

    @Column(name = "target_type", nullable = false, length = 40)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderation_log_id",
            foreignKey = @ForeignKey(name = "fk_review_case_log"))
    private ContentModerationLog moderationLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_user_id",
            foreignKey = @ForeignKey(name = "fk_review_case_requester"))
    private User requesterUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id",
            foreignKey = @ForeignKey(name = "fk_review_case_owner"))
    private User ownerUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id",
            foreignKey = @ForeignKey(name = "fk_review_case_reviewer"))
    private User reviewer;

    @Column(name = "title_snapshot", length = 255)
    private String titleSnapshot;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "evidence_url", length = 500)
    private String evidenceUrl;

    @Column(name = "evidence_file_key", length = 100)
    private String evidenceFileKey;
    @Column(name = "evidence_file_name", length = 200)
    private String evidenceFileName;
    @Column(name = "evidence_file_type", length = 100)
    private String evidenceFileType;
    @Column(name = "evidence_file_size")
    private Long evidenceFileSize;

    /** OPEN/IN_REVIEW/APPROVED/REJECTED/CLOSED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "resolution_type", length = 100)
    private String resolutionType;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
