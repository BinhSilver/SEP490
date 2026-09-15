package com.jlas.shadowing.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Multi-device shadowing practice session.
 * Table: shadowing_practice_session
 */
@Entity
@Table(name = "shadowing_practice_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowingPracticeSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_shadowing_practice_session_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_shadowing_practice_session_video"))
    private Video video;

    @Column(name = "practice_mode", nullable = false, length = 20)
    private String practiceMode;

    @Column(name = "question_order", nullable = false, length = 20)
    private String questionOrder;

    @Column(name = "requested_count", nullable = false)
    private Integer requestedCount;

    @Column(name = "total_questions", nullable = false)
    private Integer totalQuestions;

    @Column(name = "current_position", nullable = false)
    @Builder.Default
    private Integer currentPosition = 0;

    @Column(name = "correct_count", nullable = false)
    @Builder.Default
    private Integer correctCount = 0;

    @Column(name = "score_total", nullable = false)
    @Builder.Default
    private Integer scoreTotal = 0;

    @Column(name = "random_seed", nullable = false)
    private Long randomSeed;

    /** ACTIVE/COMPLETED/ABANDONED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
    }
}
