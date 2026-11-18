package com.jlas.kaiwa.service.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Model trả lời của chatbot gia sư: một API kiểu OpenAI ({@code /chat/completions}), ví dụ DeepSeek qua Deploy.
 *
 * @param baseUrl          gốc API, ví dụ {@code https://api-cdn.deploy.com/api/v1}
 * @param apiKey           key của nhà cung cấp; trống thì gia sư báo chưa sẵn sàng
 * @param model            tên model, ví dụ {@code deepseek-ai/deepseek-v4.1-flash-1017}
 * @param reasoningEffort  gửi kèm {@code reasoning_effort} (none tắt phần suy nghĩ của model suy luận); trống = không gửi
 * @param connectTimeoutMs timeout kết nối
 * @param readTimeoutMs    timeout chờ trả lời (hết thời gian mà chưa nhận chữ nào thì thử lại một lần)
 */
@ConfigurationProperties(prefix = "tutor.llm")
public record TutorLlmProperties(
        @DefaultValue("https://api-cdn.deploy.com/api/v1") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("deepseek-ai/deepseek-v4.1-flash-1017") String model,
        @DefaultValue("none") String reasoningEffort,
        @DefaultValue("15000") int connectTimeoutMs,
        @DefaultValue("30000") int readTimeoutMs) {

    public TutorLlmProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.trim();
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
        connectTimeoutMs = Math.max(1_000, connectTimeoutMs);
        readTimeoutMs = Math.max(1_000, readTimeoutMs);
    }

    public String chatUrl() {
        return baseUrl + "/chat/completions";
    }

    public boolean configured() {
        return !apiKey.isEmpty() && !baseUrl.isEmpty();
    }
}
