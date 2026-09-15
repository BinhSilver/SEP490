package com.jlas.shadowing.service;

import com.jlas.shadowing.model.VocabularyEntry;
import com.jlas.shadowing.repository.VocabularyEntryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

@Service
public class DictionaryService {

    @Autowired private VocabularyEntryRepository vocabularyRepository;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> lookup(String word) throws Exception {
        String clean = word.trim();
        if (clean.isEmpty()) return Map.of("word", word, "results", List.of());

        List<VocabularyEntry> cached = vocabularyRepository
                .findTop5ByExpressionContainingOrReadingContainingOrderByIdAsc(clean, clean);
        if (!cached.isEmpty()) {
            List<Map<String, Object>> entries = cached.stream().map(entry -> Map.<String, Object>of(
                    "word", entry.getExpression(),
                    "reading", entry.getReading(),
                    "meanings", entry.getMeaning() == null ? List.of() : List.of(entry.getMeaning())
            )).toList();
            return Map.of("word", clean, "results", entries);
        }

        String encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8);
        String url = "https://jisho.org/api/v1/search/words?keyword=" + encoded;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        Map<String, Object> result = new HashMap<>();
        result.put("word", clean);
        List<Map<String, Object>> entries = new ArrayList<>();

        if (response.getStatusCode().is2xxSuccessful()) {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.path("data");
            for (JsonNode item : data) {
                JsonNode jp = item.path("japanese").get(0);
                Map<String, Object> entry = new HashMap<>();
                entry.put("word", jp.path("word").asText(jp.path("reading").asText("")));
                entry.put("reading", jp.path("reading").asText(""));
                List<String> meanings = new ArrayList<>();
                for (JsonNode sense : item.path("senses")) {
                    for (JsonNode eng : sense.path("english_definitions")) {
                        meanings.add(eng.asText());
                    }
                }
                entry.put("meanings", meanings);
                entries.add(entry);
                String expression = String.valueOf(entry.get("word"));
                String reading = String.valueOf(entry.get("reading"));
                String normalizedExpression = normalize(expression);
                String normalizedReading = normalize(reading);
                if (!vocabularyRepository.existsByNormalizedExpressionAndNormalizedReading(
                        normalizedExpression, normalizedReading)) {
                    vocabularyRepository.save(VocabularyEntry.builder()
                            .expression(expression)
                            .normalizedExpression(normalizedExpression)
                            .reading(reading)
                            .normalizedReading(normalizedReading)
                            .meaning(String.join("; ", meanings))
                            .usageScope("DICTIONARY")
                            .dictionaryStatus("PUBLISHED")
                            .build());
                }
                if (entries.size() >= 5) break;
            }
        }
        result.put("results", entries);

        return result;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT);
    }
}
