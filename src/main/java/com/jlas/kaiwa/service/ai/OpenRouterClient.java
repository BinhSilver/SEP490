package com.jlas.kaiwa.service.ai;

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
import org.springframework.web.client.RestTemplate;

@Component
public class OpenRouterClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);

    private final OpenRouterConfig config;
    private final RestTemplate restTemplate;

    public OpenRouterClient(OpenRouterConfig config) {
        this.config = config;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(60_000);
        this.restTemplate = new RestTemplate(factory);
        // Ensure UTF-8 for Japanese replies (default ISO can garble かな)
        this.restTemplate.getMessageConverters().removeIf(c -> c instanceof StringHttpMessageConverter);
        this.restTemplate.getMessageConverters().add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    public String complete(String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return complete(systemPrompt, history, userMessage, false);
    }

    public String completeGraph(String systemPrompt, List<Map<String, String>> history, String userMessage) {
        return complete(systemPrompt, history, userMessage, true);
    }

    private String complete(String systemPrompt, List<Map<String, String>> history, String userMessage, boolean graph) {
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
        body.put("max_tokens", 512);
        if (graph) {
            // Leave room for reasoning plus the full control object. Reasoning
            // must never be substituted for the NPC's JSON dialogue response.
            body.put("max_tokens", 2048);
            body.put("reasoning", Map.of("effort", "low", "exclude", true));
            body.put("response_format", graphResponseFormat());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setAcceptCharset(List.of(StandardCharsets.UTF_8));
        headers.setBearerAuth(config.getApiKey() == null ? "" : config.getApiKey().trim());
        headers.set("HTTP-Referer", config.getSiteUrl());
        headers.set("X-Title", config.getSiteName());

        try {
            // Read as String first so UTF-8 Japanese is preserved, then parse lightly via Map via Jackson-less extract
            ResponseEntity<String> rawResponse = restTemplate.exchange(
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

            String content = graph ? extractGraphContent(responseBody) : extractContent(responseBody);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("OpenRouter message content empty (model may be thinking-only or wrong id: "
                        + config.getModel() + ")");
            }
            log.info("OpenRouter OK model={} chars={}", config.getModel(), content.length());
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
