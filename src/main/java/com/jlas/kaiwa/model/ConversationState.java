package com.jlas.kaiwa.model;

import jakarta.persistence.*;

public class ConversationState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable state-machine code stored in scenario.flow_json (for example GREETING). */
    @Transient
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id", nullable = false)
    private Scenario scenario;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "state_prompt", columnDefinition = "TEXT")
    private String statePrompt;

    /** Short narration shown between two role-play scenes (not spoken by the AI role). */
    @Transient
    private String transitionText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_id")
    private ScenarioMission mission;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "next_state_id")
    private ConversationState nextState;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Scenario getScenario() { return scenario; }
    public void setScenario(Scenario scenario) { this.scenario = scenario; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatePrompt() { return statePrompt; }
    public void setStatePrompt(String statePrompt) { this.statePrompt = statePrompt; }
    public String getTransitionText() { return transitionText; }
    public void setTransitionText(String transitionText) { this.transitionText = transitionText; }
    public ScenarioMission getMission() { return mission; }
    public void setMission(ScenarioMission mission) { this.mission = mission; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
    public ConversationState getNextState() { return nextState; }
    public void setNextState(ConversationState nextState) { this.nextState = nextState; }
}
