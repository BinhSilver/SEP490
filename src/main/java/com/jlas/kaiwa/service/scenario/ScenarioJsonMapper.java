package com.jlas.kaiwa.service.scenario;

import com.jlas.kaiwa.model.ScenarioCompactDefinition;
import com.jlas.kaiwa.model.ScenarioLearningContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
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
