package com.jlas.kaiwa.model;

import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evaluation_rule")
@SQLRestriction("rule_type = 'SCORING'")
public class ScoringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType = "SCORING";

    @Column(nullable = false, length = 80)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "max_score")
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer maxScore = 100;

    @Column(name = "weight")
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer weight = 0;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @PrePersist
    void onCreate() {
        if (code == null || code.isBlank()) code = name.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getMaxScore() { return maxScore; }
    public void setMaxScore(Integer maxScore) { this.maxScore = maxScore; }
    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }
    public String getCode() { return code; }
    public boolean isEnabled() { return enabled; }
}
