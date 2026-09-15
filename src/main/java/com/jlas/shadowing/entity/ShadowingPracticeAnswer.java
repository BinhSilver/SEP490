package com.jlas.shadowing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Answer record for shadowing practice item.
 * Table: shadowing_practice_answer
 */
@Entity
@Table(name = "shadowing_practice_answer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowingPracticeAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "practice_item_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_shadowing_practice_answer_item"))
    private ShadowingPracticeItem practiceItem;

    @Column(name = "attempt_number", nullable = false)
    private Short attemptNumber;

    @Column(name = "user_answer", nullable = false, columnDefinition = "TEXT")
    private String userAnswer;

    @Column(name = "normalized_answer", nullable = false, columnDefinition = "TEXT")
    private String normalizedAnswer;

    /** CORRECT/PARTIAL/WRONG. */
    @Column(name = "answer_result", nullable = false, length = 20)
    private String answerResult;

    @Column(name = "score", nullable = false)
    private Byte score;

    @Column(name = "feedback", nullable = false, length = 1000)
    private String feedback;

    @Column(name = "answered_at", nullable = false, updatable = false)
    private LocalDateTime answeredAt;

    @PrePersist
    protected void onCreate() {
        answeredAt = LocalDateTime.now();
    }
}
