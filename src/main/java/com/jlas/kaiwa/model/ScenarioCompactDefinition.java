package com.jlas.kaiwa.model;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * JSON representation used by the compact schema. It deliberately contains
 * only plain data so it is safe to cache and does not hold JPA entities.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioCompactDefinition(
        Integer version,
        String initialState,
        List<Goal> goals,
        List<State> states,
        List<Hint> hints,
        Map<String, Ending> endings) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Goal(
            String code,
            String title,
            String description,
            String type,
            Integer displayOrder,
            Boolean required,
            String parentCode,
            List<Condition> conditions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record State(
            String code,
            String name,
            @JsonAlias("prompt") String systemInstruction,
            String transitionText,
            List<String> allowedGoalCodes,
            @JsonAlias("next") String nextStateCode,
            List<String> nextStateCodes,
            List<String> suggestedReplies) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hint(
            String code,
            String trigger,
            @JsonAlias("title") String title,
            @JsonAlias("content") String text,
            Integer displayOrder) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ending(
            String code,
            String title,
            String message,
            String condition,
            String nextAction) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Condition(
            @JsonAlias("type") String conditionType,
            @JsonAlias("value") String matchValue) {}
}
