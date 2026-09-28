package com.jlas.learning.entity;

import com.jlas.learning.enums.LearningPackageStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import com.jlas.dictionary.entity.Lesson;

/** [JLAS-W0193] One resumable day package in an interactive learning path. */
@Entity
@Table(name = "learning_path_package", uniqueConstraints = {
        @UniqueConstraint(name = "uk_learning_package_path_day", columnNames = {"learning_path_id", "day_number"}),
        @UniqueConstraint(name = "uk_learning_package_path_order", columnNames = {"learning_path_id", "order_index"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LearningPathPackage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "learning_path_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_learning_package_path"))
    private UserLearningPath learningPath;

    /** Nullable only for rows created before migration 011; every newly composed V2 package sets it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_lesson_id", foreignKey = @ForeignKey(name = "fk_learning_package_source_lesson"))
    private Lesson sourceLesson;

    /** Canonical membership fingerprint; nullable only for pre-011 compatibility. */
    @Column(name = "content_revision", length = 64)
    private String contentRevision;

    @Column(name = "stage_id")
    private Long stageId;

    @Column(name = "phase", nullable = false, length = 20)
    private String phase;

    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private LearningPackageStatus status = LearningPackageStatus.LOCKED;

    @Column(name = "estimated_minutes", nullable = false)
    private Integer estimatedMinutes;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

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
