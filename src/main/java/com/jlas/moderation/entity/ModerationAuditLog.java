package com.jlas.moderation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Append-only audit row for one moderation state transition (JLAS-W0250).
 * Table: moderation_audit_log. No setters: rows are never edited.
 */
@Entity
@Table(name = "moderation_audit_log")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModerationAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_role", nullable = false, length = 50)
    private String actorRole;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "target_type", nullable = false, length = 40)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "from_state", length = 40)
    private String fromState;

    @Column(name = "to_state", length = 40)
    private String toState;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
