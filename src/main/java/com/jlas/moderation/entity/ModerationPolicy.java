package com.jlas.moderation.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Cấu hình moderation theo term hoặc threshold.
 * Table: moderation_policy
 */
@Entity
@Table(name = "moderation_policy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModerationPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TERM/THRESHOLD. */
    @Column(name = "policy_type", nullable = false, length = 20)
    private String policyType;

    @Column(name = "policy_key", nullable = false, length = 255)
    private String policyKey;

    @Column(name = "language", nullable = false, length = 10)
    @Builder.Default
    private String language = "ALL";

    @Column(name = "action", length = 20)
    private String action;

    @Column(name = "config_json", columnDefinition = "json")
    private String configJson;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by",
            foreignKey = @ForeignKey(name = "fk_moderation_policy_updater"))
    private User updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
