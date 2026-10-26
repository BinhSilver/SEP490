package com.jlas.kaiwa.service.ai;

import com.jlas.kaiwa.config.OpenRouterConfig;
import java.util.HashMap;
import java.util.Map;

/**
 * Chuyển body kiểu OpenAI {@code /chat/completions} sang API gốc {@code /api/chat} của Ollama.
 * Endpoint {@code /v1} của Ollama không tắt được thinking của qwen3.5, nên chat cục bộ luôn đi đường này.
 */
public final class OllamaNativeChat {

    public static final String PATH = "/api/chat";

    private OllamaNativeChat() { }

    public static String url(OpenRouterConfig config) {
        return config.getOllamaNativeUrl() + PATH;
    }

    public static Map<String, Object> toNative(Map<String, Object> openAiBody, OpenRouterConfig config) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", openAiBody.get("model"));
        body.put("messages", openAiBody.get("messages"));
        body.put("stream", false);
        body.put("think", config.isOllamaThink());
        body.put("keep_alive", config.getOllamaKeepAlive());

        Map<String, Object> options = new HashMap<>();
        options.put("num_ctx", config.getOllamaNumCtx());
        if (openAiBody.get("temperature") != null) options.put("temperature", openAiBody.get("temperature"));
        if (openAiBody.get("max_tokens") != null) options.put("num_predict", openAiBody.get("max_tokens"));
        body.put("options", options);

        Object format = format(openAiBody.get("response_format"));
        if (format != null) body.put("format", format);
        return body;
    }

    /** {@code json_object} → {@code "json"}; {@code json_schema} → chính schema (Ollama ép đầu ra theo schema). */
    private static Object format(Object responseFormat) {
        if (!(responseFormat instanceof Map<?, ?> rf)) return null;
        if ("json_schema".equals(rf.get("type")) && rf.get("json_schema") instanceof Map<?, ?> js && js.get("schema") != null) {
            return js.get("schema");
        }
        return "json_object".equals(rf.get("type")) || "json_schema".equals(rf.get("type")) ? "json" : null;
    }

    /** Nội dung trả lời; bỏ qua trường {@code thinking} nếu model vẫn trả về. */
    public static String content(Map<?, ?> response) {
        return response.get("message") instanceof Map<?, ?> message && message.get("content") instanceof String s ? s : null;
    }
}
