package com.jlas.game.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Tàu user đã mua.
 * Table: user_spaceships
 */
@Entity
@Table(name = "user_spaceships")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSpaceship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_spaceship_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ship_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_spaceship_ship"))
    private AirDefenseSpaceship spaceship;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    private LocalDateTime purchasedAt;

    @PrePersist
    protected void onCreate() {
        purchasedAt = LocalDateTime.now();
    }
}
