package com.jlas.assessment.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Các bước giải thích một conversion rule.
 * Table: rule_pattern
 */
@Entity
@Table(name = "rule_pattern")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RulePattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_rule_pattern_rule"))
    private ConversionRule rule;

    @Column(name = "step", nullable = false)
    private Short step;

    @Column(name = "title", length = 100)
    private String title;

    @Column(name = "content", length = 500)
    private String content;
}
