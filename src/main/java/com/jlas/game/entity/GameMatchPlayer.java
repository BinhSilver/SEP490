package com.jlas.game.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Snapshot người chơi trong trận.
 * Table: game_match_player
 */
@Entity
@Table(name = "game_match_player")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameMatchPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "match_player_id")
    private Long matchPlayerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "match_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_game_match_player_match"))
    private GameMatch match;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_game_match_player_user"))
    private User user;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(name = "slot", nullable = false)
    private Integer slot;

    @Column(name = "team")
    private Integer team;

    @Column(name = "score", nullable = false)
    @Builder.Default
    private Integer score = 0;

    /** PENDING/WIN/LOSE/DRAW/FORFEIT. */
    @Column(name = "result", nullable = false, length = 16)
    @Builder.Default
    private String result = "PENDING";

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @Column(name = "disconnected", nullable = false)
    @Builder.Default
    private Boolean disconnected = false;
}
