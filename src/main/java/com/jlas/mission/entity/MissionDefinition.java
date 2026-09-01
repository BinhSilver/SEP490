package com.jlas.mission.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Định nghĩa mission dùng chung cho daily và streak.
 * Table: mission_definition
 */
@Entity
@Table(name = "mission_definition")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MissionDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 40, unique = true)
    private String code;

    /** DAILY/STREAK. */
    @Column(name = "mission_type", nullable = false, length = 20)
    private String missionType;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "target_count", nullable = false)
    @Builder.Default
    private Integer targetCount = 1;

    @Column(name = "reward_credits", nullable = false)
    @Builder.Default
    private Integer rewardCredits = 0;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

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
