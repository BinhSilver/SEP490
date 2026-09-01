package com.jlas.kaiwa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Vai AI và vai người học trong scenario.
 * Table: scenario_role
 */
@Entity
@Table(name = "scenario_role")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScenarioRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AI hoặc LEARNER. */
    @Column(name = "role_kind", nullable = false, length = 20)
    private String roleKind;

    @Column(name = "code", nullable = false, length = 80)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "personality", columnDefinition = "TEXT")
    private String personality;

    @Column(name = "speech_style", columnDefinition = "TEXT")
    private String speechStyle;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
