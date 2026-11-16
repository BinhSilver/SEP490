package com.jlas.kaiwa.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.config.OpenRouterConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Model chat trên máy chủ AI (Ollama), dùng cho chatbot gia sư. Khác {@link OpenRouterClient} của AI Call:
 * luôn gọi Ollama cục bộ bất kể {@code kaiwa.llm.provider}, và khi Ollama lỗi thì báo lỗi chứ không chuyển sang API cloud.
 */
@Component
public class LocalLlmClient {

    private static final Logger log = LoggerFactory.getLogger(LocalLlmClient.class);

    private final OpenRouterConfig config;
    private final ObjectMapper json;
    private final RestTemplate restTemplate;

    public LocalLlmClient(OpenRouterConfig config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    public String model() {
        return config.getOllamaModel();
    }

    /** @param history các lượt trước, mỗi phần tử có {@code role} (user/assistant) và {@code content} */
    public String chat(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) messages.addAll(history);
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getOllamaModel());
        body.put("messages", messages);
        body.put("temperature", 0.3);
        body.put("max_tokens", maxTokens);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] raw = restTemplate.postForObject(OllamaNativeChat.url(config),
                    new HttpEntity<>(OllamaNativeChat.toNative(body, config), headers), byte[].class);
            String content = raw == null ? null
                    : OllamaNativeChat.content(json.readValue(new String(raw, StandardCharsets.UTF_8), Map.class));
            if (content == null || content.isBlank()) {
                throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model trên máy chủ AI không trả lời.");
            }
            return content.trim();
        } catch (ResourceAccessException e) {
            // Không đưa địa chỉ nội bộ ra người dùng; chi tiết nằm trong log.
            log.warn("Local LLM unreachable model={}: {}", config.getOllamaModel(), e.getMessage());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Máy chủ AI chưa sẵn sàng, bạn thử lại sau nhé.");
        } catch (RestClientException | JsonProcessingException e) {
            log.warn("Local LLM error model={}: {}", config.getOllamaModel(), e.getMessage());
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Máy chủ AI trả lỗi, bạn thử lại sau nhé.");
        }
    }
}
