package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** [JLAS-W0193] One resumable study session per V2 activity; holds the membership revision being
 * studied so a reload resumes the same session and a replan (new revision) resets cleanly. */
@Entity
@Table(name = "learning_study_session", uniqueConstraints = @UniqueConstraint(
        name = "uk_learning_study_session_activity", columnNames = "activity_item_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningStudySession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_item_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_study_session_item"))
    private UserLearningPathItem activity;

    @Column(name = "source_revision", nullable = false, length = 64)
    private String sourceRevision;

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
