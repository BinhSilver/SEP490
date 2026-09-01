package com.jlas.assessment.entity;

import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Unit;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Đầu bài/bộ câu hỏi dùng chung.
 * Table: assessment_quiz
 */
@Entity
@Table(name = "assessment_quiz")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssessmentQuiz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id",
            foreignKey = @ForeignKey(name = "fk_assessment_quiz_lesson"))
    private Lesson lesson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id",
            foreignKey = @ForeignKey(name = "fk_assessment_quiz_unit"))
    private Unit unit;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** LESSON/RULE_PRACTICE/UNIT_FINAL/JLPT_SECTION. */
    @Column(name = "quiz_type", nullable = false, length = 30)
    private String quizType;

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
