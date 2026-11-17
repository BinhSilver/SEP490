package com.jlas.kaiwa.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Model chat của chatbot gia sư, tách khỏi {@link OpenRouterClient} của AI Call: một API kiểu OpenAI
 * ({@code tutor.llm.*}, ví dụ DeepSeek qua Deploy). Model lỗi thì báo lỗi, không tự chuyển sang nhà cung cấp khác.
 */
@Component
@EnableConfigurationProperties(TutorLlmProperties.class)
public class TutorLlmClient {

    private static final Logger log = LoggerFactory.getLogger(TutorLlmClient.class);

    private final TutorLlmProperties config;
    private final ObjectMapper json;
    private final HttpClient http;

    public TutorLlmClient(TutorLlmProperties config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.connectTimeoutMs())).build();
    }

    public String model() {
        return config.model();
    }

    /** @param history các lượt trước, mỗi phần tử có {@code role} (user/assistant) và {@code content} */
    public String chat(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens) {
        return send(systemPrompt, history, userMessage, maxTokens, null);
    }

    /**
     * Như {@link #chat} nhưng model trả từng mẩu chữ (SSE); mỗi mẩu được đưa cho {@code onDelta} ngay khi tới.
     * @return toàn bộ câu trả lời
     */
    public String chatStream(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens,
                             Consumer<String> onDelta) {
        return send(systemPrompt, history, userMessage, maxTokens, onDelta);
    }

    /**
     * Nhà cung cấp đôi khi treo không trả gì (đo được trên Deploy: 2/12 lượt quá 60 giây). Hết thời gian mà chưa nhận chữ
     * nào thì thử lại một lần; đã hiện chữ cho người học rồi thì không thử lại để không lặp nội dung.
     */
    private String send(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens,
                        Consumer<String> onDelta) {
        boolean[] received = {false};
        Consumer<String> tracking = onDelta == null ? null : delta -> { received[0] = true; onDelta.accept(delta); };
        try {
            return sendOnce(systemPrompt, history, userMessage, maxTokens, tracking);
        } catch (ApiException e) {
            if (e.getErrorCode() != ErrorCode.SERVICE_UNAVAILABLE || received[0] || !config.configured()) throw e;
            log.info("Tutor API retry after no response model={}", config.model());
            return sendOnce(systemPrompt, history, userMessage, maxTokens, tracking);
        }
    }

    private String sendOnce(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens,
                            Consumer<String> onDelta) {
        if (!config.configured()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Gia sư AI chưa được cấu hình model, bạn thử lại sau nhé.");
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) messages.addAll(history);
        messages.add(Map.of("role", "user", "content", userMessage));
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.model());
        body.put("messages", messages);
        body.put("temperature", 0.3);
        body.put("max_tokens", maxTokens);
        body.put("stream", onDelta != null);
        if (!config.reasoningEffort().isEmpty()) body.put("reasoning_effort", config.reasoningEffort());
        try {
            HttpResponse<Stream<String>> response = http.send(HttpRequest.newBuilder(URI.create(config.chatUrl()))
                    .timeout(Duration.ofMillis(config.readTimeoutMs()))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofLines()); // charset theo Content-Type, mặc định UTF-8
            StringBuilder content = new StringBuilder();
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() >= 400) {
                    // Không đưa nội dung lỗi (có thể chứa một phần key) ra người dùng; chi tiết nằm trong log.
                    throw new IOException("HTTP " + response.statusCode() + " " + lines.collect(Collectors.joining(" ")));
                }
                if (onDelta == null) {
                    content.append(nullToEmpty(content(json.readValue(lines.collect(Collectors.joining("\n")), Map.class), "message")));
                } else {
                    for (String line : (Iterable<String>) lines::iterator) {
                        String data = line.startsWith("data:") ? line.substring(5).strip() : null;
                        if (data == null || data.isBlank()) continue;
                        if (data.equals("[DONE]")) break;
                        Map<?, ?> chunk = json.readValue(data, Map.class);
                        if (chunk.get("error") != null) throw new IOException("stream error " + chunk.get("error"));
                        String delta = content(chunk, "delta");
                        if (delta != null && !delta.isEmpty()) {
                            content.append(delta);
                            onDelta.accept(delta);
                        }
                    }
                }
            }
            if (content.toString().isBlank()) {
                throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Model AI không trả lời, bạn thử lại sau nhé.");
            }
            return content.toString().trim();
        } catch (ConnectException | HttpTimeoutException e) {
            log.warn("Tutor API unreachable model={}: {}", config.model(), e.getMessage());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Máy chủ AI chưa sẵn sàng, bạn thử lại sau nhé.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Máy chủ AI chưa sẵn sàng, bạn thử lại sau nhé.");
        } catch (IOException e) {
            log.warn("Tutor API error model={}: {}", config.model(), e.getMessage());
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Máy chủ AI trả lỗi, bạn thử lại sau nhé.");
        }
    }

    /** {@code choices[0].<key>.content}; bỏ {@code reasoning_content} của model suy luận. */
    private static String content(Map<?, ?> reply, String key) {
        if (reply.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.getFirst() instanceof Map<?, ?> choice && choice.get(key) instanceof Map<?, ?> message
                && message.get("content") instanceof String s) {
            return s;
        }
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
