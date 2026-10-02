package com.jlas.flashcard.entity;

import com.jlas.flashcard.speedrecall.SpeedRecallRules.State;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "flashcard_speed_recall_session")
@Getter @Setter
public class FlashcardSpeedRecallSession {
    @Id @Column(name = "session_id") private Long id;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id") private FlashcardStudySession session;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private State state = State.READY;
    @Enumerated(EnumType.STRING) @Column(length = 24) private State pausedFrom;
    @Column(nullable = false) private int currentQuestionIndex;
    @Column(nullable = false) private int totalQuestions;
    @Column(nullable = false) private int correctCount;
    @Column(nullable = false) private int wrongCount;
    @Column(nullable = false) private int timeoutCount;
    @Column(nullable = false) private int score;
    @Column(nullable = false) private int streak;
    @Column(nullable = false) private int bestStreak;
    @Column(nullable = false) private long totalResponseMs;
    @Column(nullable = false, length = 32) private String rulesVersion;
    @Column(nullable = false, length = 64) private String contentVersion;
    @Column(nullable = false, length = 16) private String selectionFilter;
    @Column(nullable = false) private boolean autoSubmit;
    private Long retryOfSessionId;
    private LocalDateTime feedbackUntil;
    private Long pausedRemainingMs;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime lastActivityAt;
    @Column(nullable = false) private LocalDateTime expiresAt;
    @Version private long version;
}
