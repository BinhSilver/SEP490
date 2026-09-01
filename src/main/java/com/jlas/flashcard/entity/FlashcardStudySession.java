package com.jlas.flashcard.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Một phiên học/quiz flashcard.
 * Table: flashcard_study_session
 */
@Entity
@Table(name = "flashcard_study_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlashcardStudySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flashcard_session_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "set_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_flashcard_session_set"))
    private FlashcardSet set;

    /** FLASHCARD/QUIZ. */
    @Column(name = "session_type", nullable = false, length = 20)
    private String sessionType;

    @Column(name = "mode", nullable = false, length = 30)
    private String mode;

    /** IN_PROGRESS/COMPLETED/ABANDONED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "IN_PROGRESS";

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
    }
}
