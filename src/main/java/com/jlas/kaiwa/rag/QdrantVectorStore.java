package com.jlas.kaiwa.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Qdrant qua REST API. Không thêm thư viện client; chỉ dùng vài endpoint cố định. */
@Component
public class QdrantVectorStore implements RagVectorStore {

    private static final int SCROLL_PAGE = 256;

    private final RagProperties properties;
    private final ObjectMapper json;
    private final RestTemplate http;

    public QdrantVectorStore(RagProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(properties.timeoutMs(), 5_000));
        factory.setReadTimeout(properties.timeoutMs());
        this.http = new RestTemplate(factory);
        this.http.getMessageConverters().removeIf(c -> c instanceof StringHttpMessageConverter);
        this.http.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<Integer> dimension(String collection) {
        try {
            JsonNode vectors = call(HttpMethod.GET, "/collections/" + collection, null).path("result")
                    .path("config").path("params").path("vectors");
            return vectors.path("size").isInt() ? Optional.of(vectors.path("size").asInt()) : Optional.empty();
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public void createCollection(String collection, int dimension) {
        call(HttpMethod.PUT, "/collections/" + collection, Map.of("vectors", Map.of("size", dimension, "distance", "Cosine")));
        // Chỉ mục payload cho bộ lọc theo bài và loại nguồn.
        call(HttpMethod.PUT, "/collections/" + collection + "/index?wait=true", Map.of("field_name", "lesson_id", "field_schema", "integer"));
        call(HttpMethod.PUT, "/collections/" + collection + "/index?wait=true", Map.of("field_name", "source_type", "field_schema", "keyword"));
    }

    @Override
    public Map<String, String> hashes(String collection, Long lessonId) {
        Map<String, String> result = new HashMap<>();
        Object offset = null;
        do {
            Map<String, Object> body = new HashMap<>();
            body.put("limit", SCROLL_PAGE);
            body.put("with_vector", false);
            body.put("with_payload", Map.of("include", List.of("content_hash")));
            if (offset != null) body.put("offset", offset);
            if (lessonId != null) body.put("filter", Map.of("must", List.of(Map.of("key", "lesson_id", "match", Map.of("value", lessonId)))));
            JsonNode page = call(HttpMethod.POST, "/collections/" + collection + "/points/scroll", body).path("result");
            for (JsonNode point : page.path("points")) {
                result.put(point.path("id").asText(), point.path("payload").path("content_hash").asText(""));
            }
            JsonNode next = page.path("next_page_offset");
            offset = next.isMissingNode() || next.isNull() ? null : next.asText();
        } while (offset != null);
        return result;
    }

    @Override
    public void upsert(String collection, List<Point> points) {
        if (points.isEmpty()) return;
        List<Map<String, Object>> body = new ArrayList<>();
        for (Point point : points) body.add(Map.of("id", point.id(), "vector", point.vector(), "payload", point.payload()));
        call(HttpMethod.PUT, "/collections/" + collection + "/points?wait=true", Map.of("points", body));
    }

    @Override
    public void delete(String collection, Collection<String> ids) {
        if (ids.isEmpty()) return;
        call(HttpMethod.POST, "/collections/" + collection + "/points/delete?wait=true", Map.of("points", List.copyOf(ids)));
    }

    @Override
    public List<Hit> search(String collection, float[] vector, int limit, double minScore) {
        JsonNode result = call(HttpMethod.POST, "/collections/" + collection + "/points/search",
                Map.of("vector", vector, "limit", limit, "with_payload", true, "score_threshold", minScore)).path("result");
        List<Hit> hits = new ArrayList<>();
        for (JsonNode hit : result) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = json.convertValue(hit.path("payload"), LinkedHashMap.class);
            hits.add(new Hit(hit.path("id").asText(), hit.path("score").asDouble(), payload));
        }
        return hits;
    }

    @Override
    public List<Hit> byPattern(String collection, String fragment, int limit) {
        // Không có full-text index nên match.text của Qdrant là so khớp chuỗi con chính xác.
        Map<String, Object> filter = Map.of("must", List.of(
                Map.of("key", "pattern_key", "match", Map.of("text", fragment)),
                Map.of("key", "source_type", "match", Map.of("any", List.of("GRAMMAR", "JLPT_GRAMMAR", "CONFUSABLE")))));
        JsonNode points = call(HttpMethod.POST, "/collections/" + collection + "/points/scroll",
                Map.of("limit", limit, "with_vector", false, "with_payload", true, "filter", filter)).path("result").path("points");
        List<Hit> hits = new ArrayList<>();
        for (JsonNode point : points) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = json.convertValue(point.path("payload"), LinkedHashMap.class);
            hits.add(new Hit(point.path("id").asText(), 1.0, payload));
        }
        return hits;
    }

    @Override
    public long count(String collection) {
        return call(HttpMethod.POST, "/collections/" + collection + "/points/count", Map.of("exact", true))
                .path("result").path("count").asLong();
    }

    private JsonNode call(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!properties.qdrantApiKey().isBlank()) headers.set("api-key", properties.qdrantApiKey().strip());
        try {
            String raw = http.exchange(properties.qdrantUrl() + path, method,
                    new HttpEntity<>(body == null ? null : json.writeValueAsString(body), headers), String.class).getBody();
            return raw == null ? json.createObjectNode() : json.readTree(raw);
        } catch (HttpClientErrorException.NotFound e) {
            throw e;
        } catch (ResourceAccessException e) {
            // Không trả địa chỉ nội bộ hay stack trace ra ngoài.
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Không kết nối được Qdrant.");
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Qdrant trả lỗi.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Qdrant response not JSON", e);
        }
    }
}
