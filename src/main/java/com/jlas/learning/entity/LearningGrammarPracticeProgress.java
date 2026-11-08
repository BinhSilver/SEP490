package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** [JLAS-W0193] Task 6 — per (activity, grammar point) ordering + fill-blank practice-gate evidence.
 * Grammar has no flip/remember flashcard step, so this table is the Grammar-specific prerequisite the
 * shared {@code InteractiveStudyEngine} consults (via {@code InteractiveContentAdapter.assertPracticeGateSatisfied})
 * before the micro-check may be issued — the engine's attempt/cooldown/review/idempotent-submit state
 * machine itself is unchanged and unaware of this table. {@code sourceRevision} mirrors
 * {@code learning_activity_member.source_revision}: a re-composition bumps the revision, and stale progress
 * rows are treated as not-yet-done rather than silently carried over to different content. */
@Entity
@Table(name = "learning_grammar_practice_progress", uniqueConstraints = @UniqueConstraint(
        name = "uk_grammar_practice_identity", columnNames = {"activity_item_id", "grammar_point_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningGrammarPracticeProgress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_item_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_grammar_practice_activity"))
    private UserLearningPathItem activity;

    @Column(name = "grammar_point_id", nullable = false)
    private Long grammarPointId;

    @Column(name = "source_revision", nullable = false, length = 64)
    private String sourceRevision;

    @Column(name = "ordering_done", nullable = false)
    @Builder.Default
    private boolean orderingDone = false;

    @Column(name = "fill_done", nullable = false)
    @Builder.Default
    private boolean fillDone = false;

    @Column(name = "ordering_done_at")
    private LocalDateTime orderingDoneAt;

    @Column(name = "fill_done_at")
    private LocalDateTime fillDoneAt;

    @Version
    @Column(name = "row_version", nullable = false)
    @Builder.Default
    private Long rowVersion = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isGateSatisfied() {
        return orderingDone && fillDone;
    }

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
