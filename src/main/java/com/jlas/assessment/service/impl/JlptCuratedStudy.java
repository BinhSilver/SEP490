package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JlptCuratedStudy {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Optional<JsonNode>> cache = new ConcurrentHashMap<>();

    public Optional<JsonNode> find(String code) {
        if (code == null) return Optional.empty();
        return findInDatabase(code).or(() -> findInResources(code));
    }

    private Optional<JsonNode> findInDatabase(String code) {
        try {
            return jdbc.query("""
                    SELECT i.item_kind, i.payload
                    FROM jlpt_exam e
                    JOIN jlpt_exam_study_list l ON l.exam_id = e.id
                    LEFT JOIN jlpt_exam_study_item i ON i.exam_id = l.exam_id
                        AND i.item_kind IN ('VOCABULARY', 'GRAMMAR')
                    WHERE e.exam_code = ?
                    ORDER BY i.item_kind, i.sort_order
                    """, rows -> {
                if (!rows.next()) return Optional.empty();
                var data = mapper.createObjectNode();
                var vocabulary = data.putArray("vocabulary");
                var grammar = data.putArray("grammar");
                do {
                    var kind = rows.getString("item_kind");
                    if (kind == null) continue;
                    var items = "VOCABULARY".equals(kind) ? vocabulary : grammar;
                    try {
                        items.add(mapper.readTree(rows.getString("payload")));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Không đọc được dữ liệu ôn tập " + code, e);
                    }
                } while (rows.next());
                return Optional.of(data);
            }, code);
        } catch (BadSqlGrammarException e) {
            // Older databases can still serve the packaged lists before the import.
            if ("42S02".equals(e.getSQLException().getSQLState())) return Optional.empty();
            throw e;
        }
    }

    private Optional<JsonNode> findInResources(String code) {
        if (!code.matches("N[1-3]_\\d{4}_(07|12)")) return Optional.empty();
        return cache.computeIfAbsent(code, key -> {
            var file = new ClassPathResource("jlpt-study/" + key.substring(0, 2) + "/" + key + ".json");
            if (!file.exists()) return Optional.empty();
            try (var input = file.getInputStream()) {
                var data = mapper.readTree(input);
                if (!key.equals(data.path("code").asText()) || !data.path("vocabulary").isArray() || !data.path("grammar").isArray()) {
                    throw new IOException("Invalid curated study resource: " + key);
                }
                return Optional.of(data);
            } catch (IOException e) { throw new IllegalStateException("Không đọc được dữ liệu ôn tập " + key, e); }
        });
    }
}
