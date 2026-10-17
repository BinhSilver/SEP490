package com.jlas.admin.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin_audit_log", indexes = {
        @Index(name = "idx_admin_audit_created", columnList = "created_at"),
        @Index(name = "idx_admin_audit_actor_created", columnList = "actor_user_id, created_at"),
        @Index(name = "idx_admin_audit_target", columnList = "target_type, target_id"),
        @Index(name = "idx_admin_audit_action_created", columnList = "action, created_at")
})
@Check(constraints = "result IN ('SUCCESS','REJECTED')")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AdminAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_admin_audit_actor"))
    private User actor;

    @Column(nullable = false, length = 50)
    private String action;
    @Column(name = "target_type", nullable = false, length = 40)
    private String targetType;
    @Column(name = "target_id", length = 64)
    private String targetId;
    @Column(length = 500)
    private String reason;
    @Column(nullable = false, length = 20)
    private String result;
    @Column(name = "changes_json", columnDefinition = "TEXT")
    private String changesJson;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
