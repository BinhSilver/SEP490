package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** [JLAS-W0193] Task 7 — server-owned Dialogue completion evidence: one row per activity. Dialogue has no
 * micro-check (no score, no attempt cycle), so this is not built on {@code InteractiveStudyEngine}; it is a
 * standalone time-and-scroll evidence ledger. {@code accumulatedSeconds} only ever grows by a server-computed,
 * capped, gap-reset contribution (see {@code InteractiveDialogueServiceImpl}) — the client never supplies a
 * duration or total directly. {@code sourceRevision} mirrors {@code learning_activity_member.source_revision}:
 * a re-composition resets stale progress rather than silently carrying it over to different dialogue content. */
@Entity
@Table(name = "learning_dialogue_progress", uniqueConstraints = @UniqueConstraint(
        name = "uk_dialogue_progress_activity", columnNames = {"activity_item_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningDialogueProgress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_item_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_dialogue_progress_activity"))
    private UserLearningPathItem activity;

    @Column(name = "source_revision", nullable = false, length = 64)
    private String sourceRevision;

    /** Server-computed accumulated active-visible seconds; never set directly from a client-reported value. */
    @Column(name = "accumulated_seconds", nullable = false)
    @Builder.Default
    private int accumulatedSeconds = 0;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    /** Client-generated nonce for the most recently accepted heartbeat, used to reject an exact-retry replay
     * without re-crediting time. Not a source of ownership or trust for any value other than "already seen". */
    @Column(name = "last_heartbeat_token", length = 80)
    private String lastHeartbeatToken;

    /** Set once, server-side, by the dedicated reached-bottom endpoint; never inferred from a client flag on
     * the heartbeat or from elapsed time alone. */
    @Column(name = "reached_bottom_at")
    private LocalDateTime reachedBottomAt;

    /** Set exactly once, when both {@code accumulatedSeconds >= REQUIRED_SECONDS} and
     * {@code reachedBottomAt != null} become true; never cleared afterward. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

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
