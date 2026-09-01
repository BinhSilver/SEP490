package com.jlas.learning.entity;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Nguồn tiến độ lesson duy nhất.
 * Table: lesson_progress
 */
@Entity
@Table(name = "lesson_progress")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_lesson_progress_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_lesson_progress_lesson"))
    private Lesson lesson;

    /** NOT_STARTED/IN_PROGRESS/COMPLETED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "NOT_STARTED";

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
