package com.jlas.game.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Kết quả Memory Match của từng user.
 * Table: memory_match_result
 */
@Entity
@Table(name = "memory_match_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryMatchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id",
            foreignKey = @ForeignKey(name = "fk_memory_result_match"))
    private GameMatch match;

    @Column(name = "room_id", length = 64)
    private String roomId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_memory_result_user"))
    private User user;

    @Column(name = "play_mode", nullable = false, length = 16)
    private String playMode;

    @Column(name = "objective", nullable = false, length = 16)
    private String objective;

    @Column(name = "pair_mode", nullable = false, length = 32)
    private String pairMode;

    @Column(name = "jlpt_level", nullable = false, length = 10)
    private String jlptLevel;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @Column(name = "board_size", nullable = false)
    private Integer boardSize;

    @Column(name = "pairs_found", nullable = false)
    @Builder.Default
    private Integer pairsFound = 0;

    @Column(name = "mistakes", nullable = false)
    @Builder.Default
    private Integer mistakes = 0;

    @Column(name = "moves", nullable = false)
    @Builder.Default
    private Integer moves = 0;

    @Column(name = "best_streak", nullable = false)
    @Builder.Default
    private Integer bestStreak = 0;

    @Column(name = "accuracy_percent")
    private Integer accuracyPercent;

    @Column(name = "average_decision_ms")
    private Integer averageDecisionMs;

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
