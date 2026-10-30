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
        List<Edge> edges,
        List<String> terminalStateCodes,
        List<BranchPath> paths,
        Map<String, Ending> endings,
        List<ScenarioStage> stages,
        List<ScenarioSpeaker> speakers) {

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
            List<String> suggestedReplies,
            String summary, String icon, Integer column, Integer row,
            String speakerCode, String openingLine, String imageUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(String source, String target, String label, List<String> requiredGoalCodes, List<String> entryGoalCodes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BranchPath(String code, String title, List<String> stateCodes) {}

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
