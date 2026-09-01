package com.jlas.mission.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Tiến độ và trạng thái nhận thưởng mission của user.
 * Table: user_mission
 */
@Entity
@Table(name = "user_mission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_mission_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_definition_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_mission_definition"))
    private MissionDefinition missionDefinition;

    /** Ngày YYYY-MM-DD hoặc LIFETIME. */
    @Column(name = "occurrence_key", nullable = false, length = 64)
    private String occurrenceKey;

    @Column(name = "progress_count", nullable = false)
    @Builder.Default
    private Integer progressCount = 0;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

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
