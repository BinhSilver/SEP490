package com.jlas.mission.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Tổng hoạt động học theo ngày.
 * Table: user_daily_progress
 */
@Entity
@Table(name = "user_daily_progress")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDailyProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_daily_progress_user"))
    private User user;

    @Column(name = "progress_date", nullable = false)
    private LocalDate progressDate;

    @Column(name = "vocabulary_learned", nullable = false)
    @Builder.Default
    private Integer vocabularyLearned = 0;

    @Column(name = "quizzes_completed", nullable = false)
    @Builder.Default
    private Integer quizzesCompleted = 0;

    @Column(name = "study_minutes", nullable = false)
    @Builder.Default
    private Integer studyMinutes = 0;

    @Column(name = "target_completed_at")
    private LocalDateTime targetCompletedAt;

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
