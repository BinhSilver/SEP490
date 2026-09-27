package com.jlas.learning.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
 * Client gọi Gemini (generativelanguage.googleapis.com) cho lớp cá nhân hoá learning path.
 *
 * <p>[JLAS-W0108] Theo pattern của {@code shadowing/service/OpenRouterService.java}: RestTemplate +
 * ObjectMapper, config qua {@code @Value}, JSON repair + retry. Mọi lời gọi là <b>best-effort</b> —
 * trả {@code null} khi chưa cấu hình key / lỗi / rate-limit để luồng gọi luôn có fallback không-Gemini.</p>
 */
@Component
public class GeminiClient implements AiJsonGenerator {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Key do user đặt trong application-local.yaml dưới tên {@code gemini-api-key} (hoặc env GEMINI_API_KEY). */
    @Value("${gemini-api-key:${GEMINI_API_KEY:}}")
    private String apiKey;

    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta}")
    private String apiUrl;

    @Value("${gemini.model:gemini-3.8-flash}")
    private String model;

    @Value("${gemini.temperature:0}")
    private double temperature;

    @Value("${gemini.max-output-tokens:4096}")
    private int maxOutputTokens;

    @Value("${gemini.json.max-retries:2}")
    private int jsonMaxRetries;

    public GeminiClient() {
        this.restTemplate = new RestTemplate();
        this.restTemplate.getMessageConverters().add(0,
                new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    /**
     * Gửi prompt tới Gemini và trả về JSON đã strip code-fence + repair (object hoặc array).
     *
     * @return {@link JsonNode} hợp lệ, hoặc {@code null} nếu Gemini không dùng được (best-effort).
     */
    @Override
    public JsonNode generateJson(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Gemini API key chưa cấu hình — bỏ qua gọi AI.");
            return null;
        }
        for (int attempt = 1; attempt <= jsonMaxRetries; attempt++) {
            try {
                String content = callGenerateContent(systemPrompt, userPrompt);
                if (content == null || content.isBlank()) {
                    continue;
                }
                JsonNode node = JsonContentParser.extractJson(content);
                if (node != null) {
                    return node;
                }
                log.warn("Gemini trả JSON không hợp lệ (attempt {}/{})", attempt, jsonMaxRetries);
            } catch (Exception e) {
                log.warn("Gemini call attempt {}/{} error: {}", attempt, jsonMaxRetries, e.getMessage());
            }
        }
        return null;
    }

    private String callGenerateContent(String systemPrompt, String userPrompt) throws Exception {
        String url = apiUrl + "/models/" + model + ":generateContent?key=" + apiKey;

        Map<String, Object> body = new LinkedHashMap<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        }
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))));
        body.put("generationConfig", Map.of(
                "temperature", temperature,
                "maxOutputTokens", maxOutputTokens));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = postWithRetry(url, entity, 3);
        if (response.getStatusCode() != HttpStatus.OK) {
            return null;
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return null;
        }
        JsonNode parts = candidates.get(0).path("content").path("parts");
        StringBuilder text = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                // Gemini 3.x (thinking) có thể tách phần suy luận; chỉ lấy text thật.
                if (part.path("thought").asBoolean(false)) {
                    continue;
                }
                String value = part.path("text").asText("");
                if (!value.isBlank()) {
                    text.append(value);
                }
            }
        }
        return text.toString();
    }

    private ResponseEntity<String> postWithRetry(String url, HttpEntity<Map<String, Object>> entity, int maxRetries) {
        int delayMs = 2000;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return restTemplate.postForEntity(url, entity, String.class);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    log.warn("Gemini rate limit, retry trong {}ms (attempt {})", delayMs, attempt + 1);
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
        throw new RuntimeException("Gemini request failed after retries");
    }
}
