package com.jlas.kaiwa.service.scenario;

import com.jlas.kaiwa.model.ScenarioCompactDefinition;
import com.jlas.kaiwa.model.ScenarioLearningContent;
import com.jlas.kaiwa.model.Scenario;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads compact JSON columns without changing the current repository/API
 * contract. Invalid or empty JSON is treated as absent so callers can fall
 * back to the legacy child-table repositories during the migration window.
 */
@Service
public class ScenarioJsonMapper {

    private static final Logger log = LoggerFactory.getLogger(ScenarioJsonMapper.class);
    private final ObjectMapper objectMapper;

    public ScenarioJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<ScenarioCompactDefinition> readFlow(String json) {
        return read(json, ScenarioCompactDefinition.class, "flow_json");
    }

    public Optional<ScenarioLearningContent> readLearningContent(String json) {
        return read(json, ScenarioLearningContent.class, "learning_content_json");
    }

    /** Share vocabulary/grammar; a case keeps its own dialogue and flow. */
    public Optional<ScenarioLearningContent> readLearningContent(Scenario scenario) {
        Optional<ScenarioLearningContent> own = readLearningContent(scenario.getLearningContentJson());
        if (scenario.getParentScenario() == null) return own;
        ScenarioLearningContent base = readLearningContent(scenario.getParentScenario().getLearningContentJson())
                .orElse(new ScenarioLearningContent(List.of(), List.of(), List.of(), List.of()));
        ScenarioLearningContent extra = own.orElse(new ScenarioLearningContent(List.of(), List.of(), List.of(), List.of()));
        return Optional.of(new ScenarioLearningContent(
                merge(base.vocabulary(), extra.vocabulary(), ScenarioLearningContent.Vocabulary::term),
                merge(base.grammar(), extra.grammar(), ScenarioLearningContent.Grammar::pattern),
                extra.exampleDialogue() == null ? List.of() : extra.exampleDialogue(),
                extra.branchDialogues() == null ? List.of() : extra.branchDialogues()));
    }

    private static <T> List<T> merge(List<T> base, List<T> extra, Function<T, String> key) {
        LinkedHashMap<String, T> items = new LinkedHashMap<>();
        if (base != null) base.forEach(item -> items.put(key.apply(item), item));
        if (extra != null) extra.forEach(item -> items.put(key.apply(item), item));
        return List.copyOf(items.values());
    }

    private <T> Optional<T> read(String json, Class<T> type, String column) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn("Ignoring invalid {} for compact scenario definition: {}", column, ex.getMessage());
            return Optional.empty();
        }
    }
}
