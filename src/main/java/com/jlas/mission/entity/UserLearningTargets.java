package com.jlas.mission.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Mục tiêu học mỗi ngày của user.
 * Table: user_learning_targets
 */
@Entity
@Table(name = "user_learning_targets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLearningTargets {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_learning_target_user"))
    private User user;

    @Column(name = "daily_vocab_target", nullable = false)
    @Builder.Default
    private Integer dailyVocabTarget = 15;

    @Column(name = "daily_quiz_target", nullable = false)
    @Builder.Default
    private Integer dailyQuizTarget = 1;

    @Column(name = "daily_study_minutes", nullable = false)
    @Builder.Default
    private Integer dailyStudyMinutes = 20;

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
