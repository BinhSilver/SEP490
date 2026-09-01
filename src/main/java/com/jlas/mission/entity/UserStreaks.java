package com.jlas.mission.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Trạng thái streak hiện tại; thưởng streak nằm trong mission_definition.
 * Table: user_streaks
 */
@Entity
@Table(name = "user_streaks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserStreaks {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_user_streak_user"))
    private User user;

    @Column(name = "current_streak", nullable = false)
    @Builder.Default
    private Integer currentStreak = 0;

    @Column(name = "longest_streak", nullable = false)
    @Builder.Default
    private Integer longestStreak = 0;

    @Column(name = "last_completed_date")
    private LocalDate lastCompletedDate;

    @Column(name = "freeze_count", nullable = false)
    @Builder.Default
    private Integer freezeCount = 0;

    @Column(name = "selected_goal_days")
    private Integer selectedGoalDays;

    @Column(name = "onboarding_step", length = 32)
    private String onboardingStep;

    @Column(name = "onboarding_completed", nullable = false)
    @Builder.Default
    private Boolean onboardingCompleted = false;

    @Column(name = "onboarding_reward_granted", nullable = false)
    @Builder.Default
    private Boolean onboardingRewardGranted = false;

    @Column(name = "reminder_time")
    private LocalTime reminderTime;

    @Column(name = "reminder_enabled", nullable = false)
    @Builder.Default
    private Boolean reminderEnabled = true;

    @Column(name = "last_freeze_used_date")
    private LocalDate lastFreezeUsedDate;

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
