package com.jlas.game.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Kinh tế/nâng cấp vĩnh viễn của Air Defense, một dòng mỗi user.
 * Table: user_permanent_upgrades
 */
@Entity
@Table(name = "user_permanent_upgrades")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPermanentUpgrades {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id",
            foreignKey = @ForeignKey(name = "fk_user_upgrade_user"))
    private User user;

    @Column(name = "coins_balance", nullable = false)
    @Builder.Default
    private Integer coinsBalance = 0;

    @Column(name = "extra_base_hp_level", nullable = false)
    @Builder.Default
    private Integer extraBaseHpLevel = 0;

    @Column(name = "coin_bonus_level", nullable = false)
    @Builder.Default
    private Integer coinBonusLevel = 0;

    @Column(name = "reroll_count_level", nullable = false)
    @Builder.Default
    private Integer rerollCountLevel = 0;

    @Column(name = "fast_start_level", nullable = false)
    @Builder.Default
    private Integer fastStartLevel = 0;

    /** Default equipped ship. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipped_ship_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_upgrade_ship"))
    private AirDefenseSpaceship equippedShip;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
