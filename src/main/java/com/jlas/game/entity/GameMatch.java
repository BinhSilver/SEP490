package com.jlas.game.entity;

import com.jlas.auth.entity.User;
import com.jlas.flashcard.entity.FlashcardSet;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Thông tin một trận; room runtime nằm trong WebSocket/in-memory.
 * Table: game_match
 */
@Entity
@Table(name = "game_match")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "match_id")
    private Long matchId;

    /** Nguồn thẻ của trận dùng flashcard. Game theo JLPT ghi nguồn trong settings_snapshot. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flashcard_set_id",
            foreignKey = @ForeignKey(name = "fk_game_match_flashcard_set"))
    private FlashcardSet flashcardSet;

    @Column(name = "room_id", nullable = false, length = 64)
    private String roomId;

    @Column(name = "room_code", nullable = false, length = 16)
    private String roomCode;

    /** CANNON_BATTLE/MEMORY_MATCH/AIR_DEFENSE. */
    @Column(name = "game_type", nullable = false, length = 32)
    private String gameType;

    /** IN_PROGRESS/COMPLETED/ABORTED. */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_user_id",
            foreignKey = @ForeignKey(name = "fk_game_match_winner"))
    private User winnerUser;

    @Column(name = "winner_team")
    private Integer winnerTeam;

    @Column(name = "settings_snapshot", columnDefinition = "json")
    private String settingsSnapshot;
}
