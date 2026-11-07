package com.jlas.kaiwa.service.ai;

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

    /** ai_usage_log feature names: the NPC reply of a turn, and the helpers (furigana, translation, suggestions). */
    static final String FEATURE_REPLY = "AI_CALL_REPLY";
    static final String FEATURE_HELPER = "AI_CALL_HELPER";

    private final OpenRouterConfig config;
    private final RestTemplate restTemplate;
    private final AiUsageRecorder usage;

    public OpenRouterClient(OpenRouterConfig config, AiUsageRecorder usage) {
        this.config = config;
        this.usage = usage;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);
        // Ensure UTF-8 for Japanese replies (default ISO can garble かな)
        this.restTemplate.getMessageConverters().removeIf(c -> c instanceof StringHttpMessageConverter);
        this.restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    public String complete(String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return complete(systemPrompt, history, userMessage, false, false);
    }

    public String completeStructured(String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return complete(systemPrompt, history, userMessage, false, true);
    }

    public String completeGraph(String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return complete(systemPrompt, history, userMessage, true, true);
    }

    private String complete(String systemPrompt, List<Map<String, String>> history, String userMessage, boolean graph, boolean structured) {
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
        Map<String, Object> reasoning = reasoning(config.getReasoningEffort());
        if (reasoning != null) body.put("reasoning", reasoning);
        if (structured) body.put("response_format", Map.of("type", "json_object"));
        if (graph) {
            // Leave room for reasoning plus the full control object. Reasoning
            // must never be substituted for the NPC's JSON dialogue response.
            body.put("max_tokens", 2048);
            body.put("reasoning", Map.of("effort", "low", "exclude", true));
            body.put("response_format", graphResponseFormat());
        }
        String providerSort = config.getProviderSort();
        if (providerSort != null && !providerSort.isBlank()) body.put("provider", Map.of("sort", providerSort.trim()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setAcceptCharset(List.of(StandardCharsets.UTF_8));
        headers.setBearerAuth(config.getApiKey() == null ? "" : config.getApiKey().trim());
        headers.set("HTTP-Referer", config.getSiteUrl());
        headers.set("X-Title", config.getSiteName());

        long started = System.nanoTime();
        try {
            // Read as String first so UTF-8 Japanese is preserved, then parse lightly via Map via Jackson-less extract
            ResponseEntity<String> rawResponse = exchangeWithRetry(
                    config.getApiUrl() + "/chat/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            if (!rawResponse.getStatusCode().is2xxSuccessful() || rawResponse.getBody() == null) {
                throw new IllegalStateException("OpenRouter empty HTTP " + rawResponse.getStatusCode());
            }

            String raw = rawResponse.getBody();
            Map<?, ?> responseBody = parseJsonMap(raw);
            if (responseBody.get("error") != null) {
                throw new IllegalStateException("OpenRouter error: " + responseBody.get("error"));
            }

            usage.recordOpenRouter(graph || structured ? FEATURE_REPLY : FEATURE_HELPER, config.getModel(), responseBody);
            String content = extractGraphContent(responseBody);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("OpenRouter message content empty (model may be thinking-only or wrong id: "
                        + config.getModel() + ")");
            }
            // provider: which host OpenRouter routed to, so a slow turn can be traced to it
            log.info("OpenRouter OK model={} provider={} ms={} chars={}", config.getModel(), responseBody.get("provider"),
                    (System.nanoTime() - started) / 1_000_000, content.length());
            return content.trim();
        } catch (HttpStatusCodeException e) {
            String bodyStr = e.getResponseBodyAsString();
            log.error("OpenRouter HTTP {}: {}", e.getStatusCode(), bodyStr);
            throw new IllegalStateException("OpenRouter HTTP " + e.getStatusCode() + ": "
                    + (bodyStr != null && bodyStr.length() > 300 ? bodyStr.substring(0, 300) : bodyStr), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenRouter call failed: {}", e.getMessage(), e);
            throw new IllegalStateException("OpenRouter call failed: " + e.getMessage(), e);
        }
    }

    private ResponseEntity<String> exchangeWithRetry(String url, HttpMethod method, HttpEntity<?> entity, Class<String> type) {
        for (int attempt = 0; ; attempt++) {
            try { return restTemplate.exchange(url, method, entity, type); }
            catch (RestClientException failure) {
                if (!isRetryable(failure) || attempt >= 1) throw failure;
                log.warn("OpenRouter transient failure, retrying once: {}", failure.getMessage());
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
            throw new IllegalStateException("OpenRouter response not JSON: " + e.getMessage(), e);
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
