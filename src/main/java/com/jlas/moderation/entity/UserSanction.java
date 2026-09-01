package com.jlas.moderation.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Lịch sử mute/ban của user.
 * Table: user_sanction
 */
@Entity
@Table(name = "user_sanction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSanction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_sanction_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_sanction_creator"))
    private User createdBy;

    /** MUTE/BAN. */
    @Column(name = "sanction_type", nullable = false, length = 20)
    private String sanctionType;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        startsAt = LocalDateTime.now();
        createdAt = LocalDateTime.now();
    }
}
