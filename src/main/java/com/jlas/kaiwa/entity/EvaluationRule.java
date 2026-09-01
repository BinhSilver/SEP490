package com.jlas.kaiwa.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Rule chấm điểm/feedback dùng chung.
 * Table: evaluation_rule
 */
@Entity
@Table(name = "evaluation_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FEEDBACK hoặc SCORING. */
    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType;

    @Column(name = "code", nullable = false, length = 80, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "priority", nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @Column(name = "max_score")
    private Short maxScore;

    @Column(name = "weight")
    private Short weight;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;
}
