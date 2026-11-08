package com.jlas.kaiwa.model;

import jakarta.persistence.*;

public class ExampleDialogue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id", nullable = false)
    private Scenario scenario;

    @Column(nullable = false, length = 20)
    private String speaker;

    @Column(name = "content_ja", nullable = false, columnDefinition = "TEXT")
    private String contentJa;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Transient
    private String translation;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Scenario getScenario() { return scenario; }
    public void setScenario(Scenario scenario) { this.scenario = scenario; }
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker; }
    public String getContentJa() { return contentJa; }
    public void setContentJa(String contentJa) { this.contentJa = contentJa; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
    public String getTranslation() { return translation; }
    public void setTranslation(String translation) { this.translation = translation; }
}
