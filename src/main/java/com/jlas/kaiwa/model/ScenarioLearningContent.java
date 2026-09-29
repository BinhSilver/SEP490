package com.jlas.kaiwa.model;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Plain JSON data for vocabulary, grammar and example dialogue. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioLearningContent(
        List<Vocabulary> vocabulary,
        List<Grammar> grammar,
        List<ExampleDialogue> exampleDialogue) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vocabulary(
            @JsonAlias("word") String term,
            String reading,
            String meaning,
            String example,
            String jlpt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Grammar(
            String pattern,
            String explanation,
            String example) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExampleDialogue(
            String speaker,
            @JsonAlias("content") String text,
            String translation) {}
}
