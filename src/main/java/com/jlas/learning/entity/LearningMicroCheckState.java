package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** [JLAS-W0193] Micro-check attempt summary per (activity, batch): three attempts per cycle,
 * a 60-minute cooldown and a mandatory review before a new cycle. Pass is decided here; the
 * immutable per-attempt snapshot stays in {@code learning_quiz_session}. */
@Entity
@Table(name = "learning_micro_check_state", uniqueConstraints = @UniqueConstraint(
        name = "uk_learning_micro_check_activity_batch", columnNames = {"activity_item_id", "batch_index"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningMicroCheckState {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_item_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_micro_check_activity"))
    private UserLearningPathItem activity;

    @Column(name = "batch_index", nullable = false)
    @Builder.Default
    private Integer batchIndex = 0;

    @Column(name = "cycle_number", nullable = false)
    @Builder.Default
    private Integer cycleNumber = 1;

    @Column(name = "attempts_used", nullable = false)
    @Builder.Default
    private Integer attemptsUsed = 0;

    @Column(name = "passed", nullable = false)
    @Builder.Default
    private boolean passed = false;

    @Column(name = "first_score")
    private Integer firstScore;

    @Column(name = "latest_score")
    private Integer latestScore;

    @Column(name = "best_score")
    private Integer bestScore;

    @Column(name = "cooldown_until")
    private LocalDateTime cooldownUntil;

    @Column(name = "review_required", nullable = false)
    @Builder.Default
    private boolean reviewRequired = false;

    @Version
    @Column(name = "row_version", nullable = false)
    @Builder.Default
    private Long rowVersion = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        var now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
