package com.jlas.kaiwa.model;

import jakarta.persistence.*;

public class ScenarioContext {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id", nullable = false, unique = true)
    private Scenario scenario;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String background;

    @Column(length = 200)
    private String location;

    @Column(name = "time_of_day", length = 100)
    private String time;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Scenario getScenario() { return scenario; }
    public void setScenario(Scenario scenario) { this.scenario = scenario; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
}
