package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Lesson chung cho curriculum và Amon/Am On/Kun.
 * Table: lesson
 */
@Entity
@Table(name = "lesson")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_lesson_unit"))
    private Unit unit;

    /** Điểm kiến thức chính cho weakness/recommendation. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "knowledge_tag_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_lesson_knowledge_tag"))
    private Tag knowledgeTag;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private Integer orderIndex = 0;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    /** CURRICULUM/AMON/REVIEW. */
    @Column(name = "lesson_type", nullable = false, length = 20)
    private String lessonType;

    /** VOCABULARY/GRAMMAR/READING/LISTENING/SPEAKING/ONYOMI/KUNYOMI. */
    @Column(name = "skill", nullable = false, length = 20)
    private String skill;

    /** TRAVEL / BA ("Công việc") / FOUNDATION cho goal-based path (chk_lesson_situation_tag). */
    @Column(name = "situation_tag", length = 50)
    private String situationTag;

    @Column(name = "is_review", nullable = false)
    @Builder.Default
    private Boolean isReview = false;

    /** DRAFT/PUBLISHED/ARCHIVED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

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
