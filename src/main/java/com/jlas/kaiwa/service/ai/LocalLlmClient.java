package com.jlas.kaiwa.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.config.OpenRouterConfig;
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
import org.springframework.stereotype.Component;

/**
 * Model chat của chatbot gia sư, tách khỏi {@link OpenRouterClient} của AI Call. Mặc định là Ollama cục bộ;
 * {@code tutor.llm.provider=api} (kèm {@code tutor.llm.api-key}) thì gọi một API kiểu OpenAI như DeepSeek.
 * Model lỗi thì báo lỗi, không tự chuyển sang nhà cung cấp khác.
 */
@Component
public class LocalLlmClient {

    private static final Logger log = LoggerFactory.getLogger(LocalLlmClient.class);

    private final OpenRouterConfig config;
    private final ObjectMapper json;
    private final HttpClient http;

    public LocalLlmClient(OpenRouterConfig config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs())).build();
    }

    public String model() {
        return config.getTutorModel();
    }

    /** Đang gọi API ngoài (DeepSeek...) thay vì Ollama cục bộ. */
    public boolean isApi() {
        return config.isTutorApi();
    }

    /** @param history các lượt trước, mỗi phần tử có {@code role} (user/assistant) và {@code content} */
    public String chat(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens) {
        return send(systemPrompt, history, userMessage, maxTokens, null);
    }

    /**
     * Như {@link #chat} nhưng model trả từng mẩu chữ; mỗi mẩu được đưa cho {@code onDelta} ngay khi tới.
     * @return toàn bộ câu trả lời
     */
    public String chatStream(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens,
                             Consumer<String> onDelta) {
        return send(systemPrompt, history, userMessage, maxTokens, onDelta);
    }

    private String send(String systemPrompt, List<Map<String, String>> history, String userMessage, int maxTokens,
                        Consumer<String> onDelta) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) messages.addAll(history);
        messages.add(Map.of("role", "user", "content", userMessage));
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getTutorModel());
        body.put("messages", messages);
        body.put("temperature", 0.3);
        body.put("max_tokens", maxTokens);
        boolean api = config.isTutorApi();
        String label = api ? "Tutor API" : "Local LLM";
        try {
            HttpRequest.Builder request;
            if (api) {
                body.put("stream", onDelta != null);
                if (!config.getTutorReasoningEffort().isEmpty()) body.put("reasoning_effort", config.getTutorReasoningEffort());
                request = HttpRequest.newBuilder(URI.create(config.getTutorChatUrl()))
                        .header("Authorization", "Bearer " + config.getTutorApiKey());
            } else {
                body = OllamaNativeChat.toNative(body, config);
                body.put("stream", onDelta != null);
                request = HttpRequest.newBuilder(URI.create(OllamaNativeChat.url(config)));
            }
            HttpResponse<Stream<String>> response = http.send(request
                    .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
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
                    Map<?, ?> reply = json.readValue(lines.collect(Collectors.joining("\n")), Map.class);
                    content.append(nullToEmpty(api ? openAiContent(reply, "message") : OllamaNativeChat.content(reply)));
                } else {
                    for (String line : (Iterable<String>) lines::iterator) {
                        String data = api ? sseData(line) : line;
                        if (data == null || data.isBlank()) continue;
                        if (data.equals("[DONE]")) break;
                        Map<?, ?> chunk = json.readValue(data, Map.class);
                        if (chunk.get("error") != null) throw new IOException("stream error " + chunk.get("error"));
                        String delta = api ? openAiContent(chunk, "delta") : OllamaNativeChat.content(chunk);
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
            log.warn("{} unreachable model={}: {}", label, config.getTutorModel(), e.getMessage());
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Máy chủ AI chưa sẵn sàng, bạn thử lại sau nhé.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Máy chủ AI chưa sẵn sàng, bạn thử lại sau nhé.");
        } catch (IOException e) {
            log.warn("{} error model={}: {}", label, config.getTutorModel(), e.getMessage());
            throw new ApiException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Máy chủ AI trả lỗi, bạn thử lại sau nhé.");
        }
    }

    /** Dòng SSE {@code data: {...}} → phần sau {@code data:}; dòng khác (chú thích, trống) → null. */
    private static String sseData(String line) {
        return line.startsWith("data:") ? line.substring(5).strip() : null;
    }

    /** {@code choices[0].<key>.content} của API kiểu OpenAI; bỏ {@code reasoning_content} của model suy luận. */
    private static String openAiContent(Map<?, ?> reply, String key) {
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
