package com.jlas.kaiwa.service.ai;

import com.jlas.common.ai.AiFeature;
import com.jlas.common.ai.AiUsageRecorder;
import com.jlas.kaiwa.config.OpenRouterConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);

    private final OpenRouterConfig config;
    private final RestTemplate restTemplate;
    private final AiUsageRecorder usage;

    public OpenRouterClient(OpenRouterConfig config, AiUsageRecorder usage) {
        this.config = config;
        this.usage = usage;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
        // Ensure UTF-8 for Japanese replies (default ISO can garble かな)
        this.restTemplate.getMessageConverters().removeIf(c -> c instanceof StringHttpMessageConverter);
        this.restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    /** A helper call (suggestion, furigana, translation); {@code feature} names it in the usage log and picks its key. */
    public String complete(AiFeature feature, String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return complete(feature, systemPrompt, history, userMessage, false, false);
    }

    public String completeStructured(String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return complete(AiFeature.AI_CALL_REPLY, systemPrompt, history, userMessage, false, true);
    }

    public String completeGraph(String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return complete(AiFeature.AI_CALL_REPLY, systemPrompt, history, userMessage, true, true);
    }

    private String complete(AiFeature feature, String systemPrompt, List<Map<String, String>> history, String userMessage,
                            boolean graph, boolean structured) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModel());
        body.put("messages", messages);
        body.put("temperature", 0.6);
        body.put("max_tokens", structured ? 2048 : 900);
        if (!config.isLocal()) {
            Map<String, Object> reasoning = reasoning(config.getReasoningEffort());
            if (reasoning != null) body.put("reasoning", reasoning);
        }
        if (structured) body.put("response_format", Map.of("type", "json_object"));
        if (graph) {
            // Leave room for reasoning plus the full control object. Reasoning
            // must never be substituted for the NPC's JSON dialogue response.
            body.put("max_tokens", 2048);
            if (!config.isLocal()) body.put("reasoning", reasoning("low"));
            body.put("response_format", graphResponseFormat());
        }
        if (!config.isLocal()) {
            String providerSort = config.getProviderSort();
            if (providerSort != null && !providerSort.isBlank()) body.put("provider", Map.of("sort", providerSort.trim()));
        }
        return send(body, feature);
    }

    /**
     * Một lượt gọi rất ngắn để kiểm tra nhà cung cấp/model đang cấu hình có trả lời được không.
     * Không trả nội dung lỗi của nhà cung cấp ra ngoài, chỉ loại lỗi.
     */
    public ProbeResult probe() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", "Trả lời đúng một từ."),
                Map.of("role", "user", "content", "「はい」をローマ字で書いてください。")));
        body.put("temperature", 0);
        body.put("max_tokens", 16);
        long started = System.nanoTime();
        try {
            String reply = send(body, null);
            return new ProbeResult(config.getProvider(), config.getModel(), true, elapsedMs(started), null, reply.length());
        } catch (IllegalStateException e) {
            String error = e.getCause() instanceof ResourceAccessException ? "UNREACHABLE_OR_TIMEOUT"
                    : e.getCause() instanceof HttpStatusCodeException status ? "HTTP_" + status.getStatusCode().value()
                    : "INVALID_RESPONSE";
            return new ProbeResult(config.getProvider(), config.getModel(), false, elapsedMs(started), error, 0);
        }
    }

    public record ProbeResult(String provider, String model, boolean ok, long latencyMs, String error, int replyChars) { }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private String label() {
        return config.isLocal() ? "Ollama" : "OpenRouter";
    }

    /**
     * @param feature tên tính năng cho ai_usage_log và key OpenRouter riêng của tính năng đó; null (probe) = key chung,
     *                không ghi chi phí. Ollama cục bộ không nhận key, header OpenRouter, và không ghi chi phí.
     */
    private String send(Map<String, Object> body, AiFeature feature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setAcceptCharset(List.of(StandardCharsets.UTF_8));
        if (!config.isLocal()) {
            String sharedKey = config.getApiKey() == null ? "" : config.getApiKey().trim();
            headers.setBearerAuth(feature == null ? sharedKey : feature.apiKey(sharedKey));
            headers.set("HTTP-Referer", config.getSiteUrl());
            headers.set("X-Title", config.getSiteName());
        }

        long started = System.nanoTime();
        try {
            // Read as String first so UTF-8 Japanese is preserved, then parse lightly via Map via Jackson-less extract
            ResponseEntity<String> rawResponse = exchangeWithRetry(
                    config.isLocal() ? OllamaNativeChat.url(config) : config.getApiUrl() + "/chat/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(config.isLocal() ? OllamaNativeChat.toNative(body, config) : body, headers),
                    String.class
            );

            if (!rawResponse.getStatusCode().is2xxSuccessful() || rawResponse.getBody() == null) {
                throw new IllegalStateException(label() + " empty HTTP " + rawResponse.getStatusCode());
            }

            String raw = rawResponse.getBody();
            Map<?, ?> responseBody = parseJsonMap(raw);
            if (responseBody.get("error") != null) {
                throw new IllegalStateException(label() + " error: " + responseBody.get("error"));
            }
            if (feature != null && !config.isLocal()) usage.recordOpenRouter(feature, config.getModel(), responseBody);

            String content = config.isLocal() ? OllamaNativeChat.content(responseBody) : extractGraphContent(responseBody);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException(label() + " message content empty (model may be thinking-only or wrong id: "
                        + config.getModel() + ")");
            }
            log.info("{} OK model={} provider={} ms={} chars={}", label(), config.getModel(), responseBody.get("provider"),
                    (System.nanoTime() - started) / 1_000_000, content.length());
            return content.trim();
        } catch (HttpStatusCodeException e) {
            String bodyStr = e.getResponseBodyAsString();
            log.error("{} HTTP {}: {}", label(), e.getStatusCode(), bodyStr);
            throw new IllegalStateException(label() + " HTTP " + e.getStatusCode() + ": "
                    + (bodyStr != null && bodyStr.length() > 300 ? bodyStr.substring(0, 300) : bodyStr), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} call failed: {}", label(), e.getMessage(), e);
            // Lỗi này hiện ra giao diện người học: chỉ nêu loại lỗi, không lộ địa chỉ nội bộ (chi tiết nằm trong log).
            throw new IllegalStateException(label() + " call failed: " + (e instanceof ResourceAccessException
                    ? "không kết nối được hoặc quá thời gian" : e.getClass().getSimpleName()), e);
        }
    }

    private ResponseEntity<String> exchangeWithRetry(String url, HttpMethod method, HttpEntity<?> entity, Class<String> type) {
        for (int attempt = 0; ; attempt++) {
            try { return restTemplate.exchange(url, method, entity, type); }
            catch (RestClientException failure) {
                if (!isRetryable(failure) || attempt >= 1) throw failure;
                log.warn("{} transient failure, retrying once: {}", label(), failure.getMessage());
                try { Thread.sleep(250L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure; }
            }
        }
    }

    static boolean isRetryable(RestClientException failure) {
        if (failure instanceof HttpStatusCodeException status)
            return status.getStatusCode().value() == 429 || status.getStatusCode().is5xxServerError();
        boolean ioFailure = failure instanceof ResourceAccessException;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            // Read timeouts have an uncertain provider outcome; don't replay them automatically.
            if (cause instanceof java.net.SocketTimeoutException && cause.getMessage() != null
                    && cause.getMessage().toLowerCase().contains("read")) return false;
            // A connection reset while the body is being read surfaces as a plain RestClientException.
            if (cause instanceof java.io.IOException) ioFailure = true;
        }
        return ioFailure;
    }

    /** The reasoning parameter for an effort; null ("off" or empty) leaves it out of the request. */
    static Map<String, Object> reasoning(String effort) {
        if (effort == null || effort.isBlank() || "off".equalsIgnoreCase(effort.trim())) return null;
        return Map.of("effort", effort.trim().toLowerCase(), "exclude", true);
    }

    static Map<String, Object> graphResponseFormat() {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("reply", Map.of("type", "string", "description", "Japanese NPC reply only"));
        fields.put("hasError", Map.of("type", "boolean"));
        for (String key : List.of("originalWrong", "corrected", "grammarReason", "mistakeType", "nextStateCode")) {
            fields.put(key, Map.of("type", List.of("string", "null")));
        }
        fields.put("objectivesMet", Map.of("type", "array", "items", Map.of("type", "string")));
        fields.put("readyToAdvanceState", Map.of("type", "boolean"));
        fields.put("readyToEnd", Map.of("type", "boolean"));
        fields.put("expressions", Map.of("type", "array", "items", Map.of("type", "string")));
        return Map.of("type", "json_schema", "json_schema", Map.of("name", "conversation_graph_turn", "strict", true,
                "schema", Map.of("type", "object", "properties", fields, "required", new ArrayList<>(fields.keySet()), "additionalProperties", false)));
    }

    private static String extractGraphContent(Map<?, ?> responseBody) {
        if (!(responseBody.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.getFirst() instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) return null;
        return stringifyContent(message.get("content"));
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> parseJsonMap(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("LLM response not JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(Map<?, ?> responseBody) {
        Object choices = responseBody.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return null;
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> msg)) {
            return null;
        }

        // Standard content
        String content = stringifyContent(msg.get("content"));
        if (content != null && !content.isBlank()) {
            return content;
        }
        // Some "thinking" models put text in reasoning / reasoning_content
        content = stringifyContent(msg.get("reasoning"));
        if (content != null && !content.isBlank()) {
            return content;
        }
        content = stringifyContent(msg.get("reasoning_content"));
        if (content != null && !content.isBlank()) {
            return content;
        }
        // Fallback: delta style
        Object delta = choice.get("delta");
        if (delta instanceof Map<?, ?> d) {
            content = stringifyContent(d.get("content"));
            if (content != null && !content.isBlank()) {
                return content;
            }
        }
        return null;
    }

    private static String stringifyContent(Object content) {
        if (content == null) {
            return null;
        }
        if (content instanceof String s) {
            return s;
        }
        // Multimodal array: [{"type":"text","text":"..."}]
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                if (p instanceof Map<?, ?> m) {
                    Object t = m.get("text");
                    if (t != null) {
                        sb.append(t);
                    }
                } else if (p != null) {
                    sb.append(p);
                }
            }
            return sb.toString();
        }
        return content.toString();
    }
}
