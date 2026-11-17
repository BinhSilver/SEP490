package com.jlas.kaiwa.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.ai.AiFeature;
import com.jlas.common.ai.AiUsageRecorder;
import com.jlas.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.config.OpenRouterConfig;
import com.jlas.kaiwa.service.ai.OllamaNativeChat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static com.jlas.kaiwa.memory.MemoryTypes.*;

@Component
public class MemoryAiClient {
    private final ObjectMapper json;
    private final MemoryProperties properties;
    private final RestTemplate chatHttp;
    private final RestTemplate embeddingHttp;
    private final String extractionPrompt;
    private final OpenRouterConfig provider;
    @Value("${kaiwa.memory.model:${openrouter.model:openai/gpt-4o-mini}}") private String model;
    @Autowired(required = false) private AiUsageRecorder usage;

    public MemoryAiClient(ObjectMapper json, MemoryProperties properties, OpenRouterConfig provider) {
        this.json = json;
        this.properties = properties;
        this.provider = provider;
        this.chatHttp = http(provider.isLocal() ? provider.getReadTimeoutMs() : 60_000);
        // A local embedding model may need a cold load from disk before its first vector.
        this.embeddingHttp = http(provider.isLocal() ? 15_000 : 4_000);
        try (var stream = new ClassPathResource("prompts/kaiwa/memory-extraction.md").getInputStream()) {
            extractionPrompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Missing memory extraction prompt", e);
        }
    }

    private static RestTemplate http(int timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeout, 5_000));
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    public boolean available() {
        if (provider.isLocal()) return true;
        String apiKey = provider.getApiKey();
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("YOUR_");
    }
    /** Index identity includes the text recipe, so old vectors are rebuilt safely. */
    public String embeddingModel() { return embeddingModelName() + "#user-evidence-v1"; }

    private String embeddingModelName() {
        return provider.isLocal() ? provider.getLocalEmbeddingModel() : properties.embeddingModel();
    }

    private String chatModel() {
        return provider.isLocal() ? provider.getModel() : model;
    }

    String endpoint(String path) {
        return provider.getApiUrl().replaceAll("/+$", "").replaceAll("/chat/completions$", "") + path;
    }

    private JsonNode post(AiFeature feature, RestTemplate http, String path, Map<String, Object> body) {
        if (!available()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Chưa cấu hình OPENROUTER_API_KEY.");
        var headers = new HttpHeaders();
        // Ollama cục bộ không nhận key và không tính chi phí; OpenRouter dùng key riêng của tính năng (nếu có) và ghi ai_usage_log.
        if (!provider.isLocal()) headers.setBearerAuth(feature.apiKey(provider.getApiKey()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String url = path.startsWith("http") ? path : endpoint(path);
            String response = http.postForObject(url, new HttpEntity<>(body, headers), String.class);
            if (usage != null && !provider.isLocal()) usage.recordOpenRouter(feature, String.valueOf(body.get("model")), response);
            return json.readTree(response);
        } catch (Exception e) {
            // Do not surface provider bodies: they may contain user conversation or credentials.
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Dịch vụ AI tạm thời không phản hồi.");
        }
    }

    public double[] embed(String text) {
        JsonNode vector = post(AiFeature.AI_CALL_MEMORY_EMBED, embeddingHttp, "/embeddings", Map.of("model", embeddingModelName(),
                "input", text, "encoding_format", "float")).path("data").path(0).path("embedding");
        if (!vector.isArray() || vector.isEmpty() || vector.size() > 8192)
            throw new IllegalStateException("Invalid embedding response");
        double[] values = new double[vector.size()];
        double norm = 0;
        for (int i = 0; i < values.length; i++) {
            if (!vector.get(i).isNumber()) throw new IllegalStateException("Invalid embedding response");
            values[i] = vector.get(i).asDouble();
            if (!Double.isFinite(values[i])) throw new IllegalStateException("Invalid embedding response");
            norm += values[i] * values[i];
        }
        if (!Double.isFinite(norm) || norm == 0) throw new IllegalStateException("Invalid embedding response");
        return values;
    }

    /** Chat cục bộ đi qua API gốc của Ollama (tắt thinking, giữ model nạp sẵn); cloud giữ nguyên /chat/completions. */
    private String chat(AiFeature feature, Map<String, Object> request) {
        if (!provider.isLocal())
            return post(feature, chatHttp, "/chat/completions", request).path("choices").path(0).path("message").path("content").asText("");
        return post(feature, chatHttp, OllamaNativeChat.url(provider), OllamaNativeChat.toNative(request, provider))
                .path("message").path("content").asText("");
    }

    public String reply(List<Map<String, String>> messages) {
        String reply = chat(AiFeature.AI_CALL_MEMORY_CHAT, Map.of("model", chatModel(), "messages", messages,
                "temperature", 0.5, "max_tokens", 900)).strip();
        if (reply.isBlank() || reply.length() > 6000)
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "AI trả về nội dung không hợp lệ.");
        return reply;
    }

    public Extraction extract(Session session, List<Memory> known) {
        try {
            var data = Map.of("now", LocalDateTime.now().toString(), "previousSummary", session.summary(),
                    "turns", session.turns().stream().filter(t -> t.id() > session.processedRevision()).toList(),
                    "knownMemories", known.stream().map(m -> Map.of("key", m.key(), "content", m.content(), "kind", m.kind(), "status", m.status())).toList());
            Map<String, Object> request = new HashMap<>(Map.of("model", chatModel(), "temperature", 0,
                    "max_tokens", 2400, "response_format", Map.of("type", "json_object"), "messages", List.of(
                    Map.of("role", "system", "content", extractionPrompt),
                    Map.of("role", "user", "content", json.writeValueAsString(data)))));
            // "reasoning" is an OpenRouter extension; never send it to a local Ollama model.
            if (!provider.isLocal()) request.put("reasoning", Map.of("effort", "low", "exclude", true));
            JsonNode result = json.readTree(chat(AiFeature.AI_CALL_MEMORY_EXTRACT, request));
            String summary = result.path("summary").asText("").strip();
            if (summary.isBlank() && !session.summary().isBlank()) summary = session.summary();
            if (summary.length() > 3000 || !result.path("facts").isArray() || result.path("facts").size() > 12)
                throw new IllegalStateException("Invalid extraction response");
            List<Fact> facts = new ArrayList<>();
            for (var node : result.path("facts")) {
                String key = node.path("key").asText("");
                String kind = node.path("kind").asText("");
                String content = node.path("content").asText("").strip();
                String topic = node.path("topic").asText("").strip();
                long evidenceId = node.path("evidenceTurnId").asLong(-1);
                String evidence = node.path("evidence").asText("").strip();
                // Only a new user utterance can create/change a personal memory; AI dialogue is not evidence.
                boolean grounded = !evidence.isBlank() && session.turns().stream().anyMatch(t ->
                        t.id() == evidenceId && t.id() > session.processedRevision() && "user".equals(t.role()) && t.content().contains(evidence));
                if (!grounded || !key.matches("[a-z0-9][a-z0-9._-]{1,119}") || !List.of("PROFILE", "GOAL", "EVENT", "PENDING", "PREFERENCE").contains(kind)
                        || content.isBlank() || content.length() > 800 || topic.isBlank() || topic.length() > 120) continue;
                LocalDateTime expires = null;
                if (!node.path("expiresAt").isNull() && !node.path("expiresAt").asText("").isBlank())
                    expires = LocalDateTime.parse(node.path("expiresAt").asText());
                facts.add(new Fact(key, topic, content, kind, Math.max(1, Math.min(5, node.path("importance").asInt(3))),
                        evidenceId, evidence, expires, node.path("active").asBoolean(true)));
            }
            return new Extraction(summary, facts);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Invalid memory extraction response"); }
    }
}
