package com.jlas.learning.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * [JLAS-W0108] Client gọi DeepSeek (OpenAI-compatible {@code chat/completions}) cho lớp cá nhân hoá
 * learning path — thay thế Gemini khi Gemini hết quota.
 *
 * <p>Giữ nguyên hợp đồng {@link AiJsonGenerator} với {@link GeminiClient}: RestTemplate + ObjectMapper,
 * JSON strip code-fence + repair (qua {@link JsonContentParser}), retry + rate-limit backoff. Mọi lời
 * gọi là <b>best-effort</b> — trả {@code null} khi chưa cấu hình key / lỗi / rate-limit.</p>
 */
@Component
public class DeepSeekClient implements AiJsonGenerator {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Key do user đặt trong application-local.yaml dưới tên {@code deepseek.api.key} (hoặc env DEEPSEEK_API_KEY). */
    @Value("${deepseek.api.key:${DEEPSEEK_API_KEY:}}")
    private String apiKey;

    @Value("${deepseek.api.url:https://api.deepseek.com}")
    private String apiUrl;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    @Value("${deepseek.temperature:0}")
    private double temperature;

    @Value("${deepseek.max-tokens:4096}")
    private int maxTokens;

    @Value("${deepseek.json.max-retries:2}")
    private int jsonMaxRetries;

    public DeepSeekClient() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.getMessageConverters().add(0,
                new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    @Override
    public JsonNode generateJson(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("DeepSeek API key chưa cấu hình — bỏ qua gọi AI.");
            return null;
        }
        for (int attempt = 1; attempt <= jsonMaxRetries; attempt++) {
            try {
                String content = callChatCompletions(systemPrompt, userPrompt);
                if (content == null || content.isBlank()) {
                    continue;
                }
                JsonNode node = JsonContentParser.extractJson(content);
                if (node != null) {
                    return node;
                }
                log.warn("DeepSeek trả JSON không hợp lệ (attempt {}/{})", attempt, jsonMaxRetries);
            } catch (Exception e) {
                log.warn("DeepSeek call attempt {}/{} error: {}", attempt, jsonMaxRetries, e.getMessage());
            }
        }
        return null;
    }

    private String callChatCompletions(String systemPrompt, String userPrompt) throws Exception {
        String url = apiUrl + "/chat/completions";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = postWithRetry(url, entity, 3);
        if (response.getStatusCode() != HttpStatus.OK) {
            return null;
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).path("message").path("content").asText("");
    }

    private ResponseEntity<String> postWithRetry(String url, HttpEntity<Map<String, Object>> entity, int maxRetries) {
        int delayMs = 2000;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return restTemplate.postForEntity(url, entity, String.class);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    log.warn("DeepSeek rate limit, retry trong {}ms (attempt {})", delayMs, attempt + 1);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    delayMs *= 2;
                } else {
                    throw e;
                }
            }
        }
        throw new RuntimeException("DeepSeek request failed after retries");
    }
}
