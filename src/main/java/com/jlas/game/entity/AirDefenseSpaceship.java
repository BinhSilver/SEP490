package com.jlas.game.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Catalog tàu trong Air Defense.
 * Table: air_defense_spaceships
 */
@Entity
@Table(name = "air_defense_spaceships")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AirDefenseSpaceship {

    @Id
    @Column(name = "ship_id", length = 64)
    private String shipId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** BALANCED/ATTACK/DEFENSE. */
    @Column(name = "role", nullable = false, length = 32)
    @Builder.Default
    private String role = "BALANCED";

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price_coins", nullable = false)
    @Builder.Default
    private Integer priceCoins = 0;

    @Column(name = "base_hp", nullable = false)
    @Builder.Default
    private Integer baseHp = 100;

    @Column(name = "speed_mult", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal speedMult = BigDecimal.ONE;

    @Column(name = "passive_skill_code", length = 64)
    private String passiveSkillCode;

    @Column(name = "color_theme", nullable = false, length = 32)
    @Builder.Default
    private String colorTheme = "cyan";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
