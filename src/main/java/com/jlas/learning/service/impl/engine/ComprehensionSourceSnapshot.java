package com.jlas.learning.service.impl.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jlas.learning.dto.ComprehensionExerciseSource;
import com.jlas.learning.enums.LearningActivityType;
import java.util.Optional;

/** [JLAS-W0108] Serialises the frozen Reading/Listening source stored in {@code learning_quiz_session.source_snapshot_json}.
 * A missing, blank or unreadable snapshot is reported as empty - callers must say "unavailable", never fall back
 * to the current canonical content. */
public final class ComprehensionSourceSnapshot {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ComprehensionSourceSnapshot() { }

    public static String toJson(LearningActivityType type, int batchIndex, String sourceRevision,
            ComprehensionExerciseSource source) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("activityType", type.name());
        node.put("batchIndex", batchIndex);
        node.put("sourceRevision", sourceRevision);
        node.put("passage", source.passage());
        node.put("audioUrl", source.audioUrl());
        node.put("transcriptJa", source.transcriptJa());
        return node.toString();
    }

    public static Optional<ComprehensionExerciseSource> parse(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode node = MAPPER.readTree(json);
            String passage = text(node, "passage");
            String audio = text(node, "audioUrl");
            if (passage == null && audio == null) return Optional.empty();
            return Optional.of(new ComprehensionExerciseSource(passage, audio, text(node, "transcriptJa")));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
