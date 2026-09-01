package com.jlas.learning.entity;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Gợi ý lesson ôn tập từ một weakness.
 * Table: user_lesson_recommendation
 */
@Entity
@Table(name = "user_lesson_recommendation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLessonRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** User nhận recommendation, giữ để truy vấn trực tiếp. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_recommendation_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "weakness_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_recommendation_weakness"))
    private UserKnowledgeWeakness weakness;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id",
            foreignKey = @ForeignKey(name = "fk_recommendation_lesson"))
    private Lesson lesson;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** PENDING/ACCEPTED/DISMISSED/COMPLETED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

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
