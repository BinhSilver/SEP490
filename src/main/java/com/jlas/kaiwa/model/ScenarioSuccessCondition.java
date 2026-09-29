package com.jlas.kaiwa.model;

import jakarta.persistence.*;

public class ScenarioSuccessCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id", nullable = false)
    private Scenario scenario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "objective_id")
    private ScenarioObjective objective;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(length = 500)
    private String description;

    @Column(name = "condition_type", nullable = false, length = 30)
    private String conditionType = "KEYWORD";

    @Column(name = "match_value", length = 500)
    private String matchValue;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Scenario getScenario() { return scenario; }
    public void setScenario(Scenario scenario) { this.scenario = scenario; }
    public ScenarioObjective getObjective() { return objective; }
    public void setObjective(ScenarioObjective objective) { this.objective = objective; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getConditionType() { return conditionType; }
    public void setConditionType(String conditionType) { this.conditionType = conditionType; }
    public String getMatchValue() { return matchValue; }
    public void setMatchValue(String matchValue) { this.matchValue = matchValue; }
}
