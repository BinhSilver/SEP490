package com.jlas.assessment.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Only the compact, actually used study lists are packaged; raw crawl files are not required. */
@Component
public class JlptCuratedStudy {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Optional<JsonNode>> cache = new ConcurrentHashMap<>();
    public Optional<JsonNode> find(String code) {
        if (code == null || !code.matches("N[1-3]_\\d{4}_(07|12)")) return Optional.empty();
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
