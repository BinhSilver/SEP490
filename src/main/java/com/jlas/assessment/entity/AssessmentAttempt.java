package com.jlas.assessment.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Supertype cho mỗi lần làm bài.
 * Table: assessment_attempt
 */
@Entity
@Table(name = "assessment_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssessmentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_assessment_attempt_user"))
    private User user;

    /** QUIZ/JLPT/SMART_TEST. */
    @Column(name = "attempt_type", nullable = false, length = 20)
    private String attemptType;

    /** IN_PROGRESS/SUBMITTED/ABANDONED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "IN_PROGRESS";

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** Legacy seed compatibility. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quiz_id",
            foreignKey = @ForeignKey(name = "fk_assessment_attempt_legacy_quiz"))
    private AssessmentQuiz quiz;

    @Column(name = "score_percent", precision = 5, scale = 2)
    private BigDecimal scorePercent;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
    }
}
