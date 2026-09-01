package com.jlas.kaiwa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Bridge scenario và rule đánh giá.
 * Table: scenario_evaluation_rule
 */
@Entity
@Table(name = "scenario_evaluation_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScenarioEvaluationRule {

    @EmbeddedId
    private ScenarioEvaluationRuleId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("scenarioId")
    @JoinColumn(name = "scenario_id",
            foreignKey = @ForeignKey(name = "fk_scenario_eval_scenario"))
    private Scenario scenario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("ruleId")
    @JoinColumn(name = "rule_id",
            foreignKey = @ForeignKey(name = "fk_scenario_eval_rule"))
    private EvaluationRule evaluationRule;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class ScenarioEvaluationRuleId implements Serializable {
        @Column(name = "scenario_id")
        private Long scenarioId;

        @Column(name = "rule_id")
        private Long ruleId;
    }
}
