package com.jlas.flashcard.entity;

import com.jlas.flashcard.speedrecall.SpeedRecallRules.Outcome;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "flashcard_speed_recall_answer",
       uniqueConstraints = @UniqueConstraint(name = "uk_sr_question_position", columnNames = {"session_id", "question_index"}))
@Getter @Setter
public class FlashcardSpeedRecallAnswer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false) private FlashcardSpeedRecallSession session;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flashcard_card_id") private FlashcardCard card;
    @Column(nullable = false) private int questionIndex;
    @Column(nullable = false, length = 500) private String questionText;
    @Column(nullable = false, length = 500) private String expectedAnswer;
    @Column(columnDefinition = "TEXT") private String meaning;
    @Column(nullable = false, length = 255) private String reading;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Outcome outcome = Outcome.PENDING;
    @Column(length = 500) private String userAnswer;
    @Column(length = 500) private String normalizedAnswer;
    private Integer responseMs;
    @Column(nullable = false) private int scoreAwarded;
    private Integer timeLimitMs;
    private Integer streakBefore;
    private LocalDateTime issuedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime resolvedAt;
    @Column(length = 36) private String answerRequestId;
    @Column(length = 64) private String requestFingerprint;
}
