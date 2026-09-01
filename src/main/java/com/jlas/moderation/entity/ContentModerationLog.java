package com.jlas.moderation.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Log kết quả moderation; có expires_at để hỗ trợ retention.
 * Table: content_moderation_log
 */
@Entity
@Table(name = "content_moderation_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentModerationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", nullable = false, length = 40)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id",
            foreignKey = @ForeignKey(name = "fk_moderation_owner"))
    private User ownerUser;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "source", nullable = false, length = 40)
    private String source;

    /** SAFE/UNSAFE/REVIEW. */
    @Column(name = "verdict", nullable = false, length = 20)
    private String verdict;

    @Column(name = "primary_category", length = 100)
    private String primaryCategory;

    @Column(name = "categories_json", columnDefinition = "json")
    private String categoriesJson;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "risk_score", nullable = false)
    @Builder.Default
    private Byte riskScore = 0;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
