package com.jlas.kaiwa.model;

import jakarta.persistence.*;

public class ScenarioVocabulary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scenario_id", nullable = false)
    private Scenario scenario;

    @Column(name = "word_ja", nullable = false, length = 200)
    private String wordJa;

    @Column(length = 200)
    private String reading;

    @Column(nullable = false, length = 300)
    private String meaning;

    @Column(name = "part_of_speech", length = 50)
    private String partOfSpeech;

    @Column(name = "jlpt_level", length = 10)
    private String jlptLevel;

    @Column(name = "example_ja", length = 500)
    private String exampleJa;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Scenario getScenario() { return scenario; }
    public void setScenario(Scenario scenario) { this.scenario = scenario; }
    public String getWordJa() { return wordJa; }
    public void setWordJa(String wordJa) { this.wordJa = wordJa; }
    public String getReading() { return reading; }
    public void setReading(String reading) { this.reading = reading; }
    public String getMeaning() { return meaning; }
    public void setMeaning(String meaning) { this.meaning = meaning; }
    public String getPartOfSpeech() { return partOfSpeech; }
    public void setPartOfSpeech(String partOfSpeech) { this.partOfSpeech = partOfSpeech; }
    public String getJlptLevel() { return jlptLevel; }
    public void setJlptLevel(String jlptLevel) { this.jlptLevel = jlptLevel; }
    public String getExampleJa() { return exampleJa; }
    public void setExampleJa(String exampleJa) { this.exampleJa = exampleJa; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
}
