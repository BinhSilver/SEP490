package com.jlas.game.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Kết quả Air Defense của từng user.
 * Table: air_defense_result
 */
@Entity
@Table(name = "air_defense_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AirDefenseResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id",
            foreignKey = @ForeignKey(name = "fk_air_result_match"))
    private GameMatch match;

    @Column(name = "room_id", length = 64)
    private String roomId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_air_result_user"))
    private User user;

    @Column(name = "play_mode", nullable = false, length = 16)
    private String playMode;

    @Column(name = "objective", nullable = false, length = 24)
    private String objective;

    @Column(name = "difficulty", nullable = false, length = 16)
    private String difficulty;

    @Column(name = "answer_mode", nullable = false, length = 32)
    private String answerMode;

    @Column(name = "jlpt_level", nullable = false, length = 10)
    private String jlptLevel;

    @Column(name = "outcome", nullable = false, length = 24)
    private String outcome;

    @Column(name = "hp_remaining", nullable = false)
    private Integer hpRemaining;

    @Column(name = "score", nullable = false)
    @Builder.Default
    private Integer score = 0;

    @Column(name = "questions_answered", nullable = false)
    @Builder.Default
    private Integer questionsAnswered = 0;

    @Column(name = "correct_answers", nullable = false)
    @Builder.Default
    private Integer correctAnswers = 0;

    @Column(name = "incorrect_answers", nullable = false)
    @Builder.Default
    private Integer incorrectAnswers = 0;

    @Column(name = "accuracy_percent", nullable = false)
    @Builder.Default
    private Integer accuracyPercent = 0;

    @Column(name = "best_combo", nullable = false)
    @Builder.Default
    private Integer bestCombo = 0;

    @Column(name = "average_response_ms")
    private Integer averageResponseMs;

    @Column(name = "duration_ms", nullable = false)
    @Builder.Default
    private Long durationMs = 0L;

    @Column(name = "ranked", nullable = false)
    @Builder.Default
    private Boolean ranked = false;

    @Column(name = "winner", nullable = false)
    @Builder.Default
    private Boolean winner = false;

    @Column(name = "finished_at", nullable = false)
    private LocalDateTime finishedAt;
}
