package com.jlas.learning.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * [JLAS-W0108] Log một lần nộp quiz (gate quiz hoặc lesson quiz) để giới hạn 5 lượt/ngày.
 * Table: quiz_attempt
 */
@Entity
@Table(name = "quiz_attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_quiz_attempt_user"))
    private User user;

    /** GATE hoặc LESSON. */
    @Column(name = "quiz_type", nullable = false, length = 20)
    private String quizType;

    /** null cho GATE; id bài học cho LESSON quiz. */
    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
