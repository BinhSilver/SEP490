package com.jlas.kaiwa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Nhà cung cấp LLM cho AI Call (kaiwa). Cả hai đều nói giao thức OpenAI {@code /chat/completions}:
 * <ul>
 *   <li>{@code openrouter} (mặc định): API cloud, cần {@code OPENROUTER_API_KEY}. Giữ nguyên hành vi cũ.</li>
 *   <li>{@code ollama}: suy luận cục bộ, không cần API key. Chat đi qua API gốc {@code /api/chat} để tắt được
 *       chế độ thinking và giữ model trong VRAM ({@code keep_alive}); embedding đi qua {@code /v1}.
 *       Không có fallback ngầm sang cloud khi Ollama lỗi.</li>
 * </ul>
 */
@Configuration
public class OpenRouterConfig {

    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_OLLAMA = "ollama";

    @Value("${openrouter.api.key}")
    private String apiKey;

    @Value("${openrouter.api.url}")
    private String apiUrl;

    @Value("${openrouter.site.url}")
    private String siteUrl;

    @Value("${openrouter.site.name}")
    private String siteName;

    @Value("${openrouter.model:qwen/qwen3-14b}")
    private String model;

    // Reasoning effort of the conversation calls (none, minimal, low, ...). "off" sends no reasoning
    // parameter at all: an Anthropic model starts thinking as soon as any effort is given.
    @Value("${openrouter.reasoning-effort:low}")
    private String reasoningEffort;

    // OpenRouter provider sort (latency, throughput, price); empty keeps OpenRouter's own routing.
    @Value("${openrouter.provider-sort:}")
    private String providerSort;

    public String getReasoningEffort() { return reasoningEffort; }
    public String getProviderSort() { return providerSort; }
    @Value("${kaiwa.llm.provider:openrouter}")
    private String provider;

    @Value("${kaiwa.llm.ollama.base-url:http://127.0.0.1:11434/v1}")
    private String ollamaBaseUrl;

    @Value("${kaiwa.llm.ollama.model:qwen3.5:9b}")
    private String ollamaModel;

    @Value("${kaiwa.llm.ollama.embedding-model:bge-m3}")
    private String ollamaEmbeddingModel;

    /** Thời gian Ollama giữ model trong bộ nhớ sau lượt gọi cuối (định dạng Ollama: 30m, 1h, -1). */
    @Value("${kaiwa.llm.ollama.keep-alive:30m}")
    private String ollamaKeepAlive;

    /** qwen3.5 mặc định suy nghĩ rất lâu (đo được: hết 2048 token mà không ra câu trả lời), nên mặc định tắt. */
    @Value("${kaiwa.llm.ollama.think:false}")
    private boolean ollamaThink;

    @Value("${kaiwa.llm.ollama.num-ctx:4096}")
    private int ollamaNumCtx;

    @Value("${kaiwa.llm.ollama.warm-up-on-startup:true}")
    private boolean ollamaWarmUp;

    @Value("${kaiwa.llm.connect-timeout-ms:15000}")
    private int connectTimeoutMs;

    @Value("${kaiwa.llm.read-timeout-ms:60000}")
    private int readTimeoutMs;

    /** {@code true} khi suy luận chạy trên Ollama cục bộ. Giá trị provider lạ bị coi là openrouter. */
    public boolean isLocal() {
        return provider != null && PROVIDER_OLLAMA.equalsIgnoreCase(provider.trim());
    }

    public String getProvider() { return isLocal() ? PROVIDER_OLLAMA : PROVIDER_OPENROUTER; }

    /** Ollama không cần key; không bao giờ gửi key OpenRouter tới endpoint cục bộ. */
    public String getApiKey() { return isLocal() ? "" : apiKey; }

    public String getApiUrl() { return isLocal() ? ollamaBaseUrl.trim().replaceAll("/+$", "") : apiUrl; }
    public String getSiteUrl() { return siteUrl; }
    public String getSiteName() { return siteName; }
    public String getModel() { return isLocal() ? ollamaModel : model; }

    /** Model chat của Ollama, kể cả khi AI Call đang dùng OpenRouter (chatbot gia sư luôn chạy cục bộ). */
    public String getOllamaModel() { return ollamaModel; }

    /** Gốc của API Ollama (bỏ hậu tố {@code /v1}), ví dụ {@code http://127.0.0.1:11434}. */
    public String getOllamaNativeUrl() { return ollamaBaseUrl.trim().replaceAll("/+$", "").replaceAll("/v1$", ""); }
    public String getOllamaKeepAlive() { return ollamaKeepAlive == null || ollamaKeepAlive.isBlank() ? "30m" : ollamaKeepAlive.trim(); }
    public boolean isOllamaThink() { return ollamaThink; }
    public int getOllamaNumCtx() { return Math.max(512, ollamaNumCtx); }
    public boolean isOllamaWarmUp() { return ollamaWarmUp; }

    /** Model embedding cục bộ; chỉ dùng khi {@link #isLocal()}. */
    public String getLocalEmbeddingModel() { return ollamaEmbeddingModel; }

    /** Model của gia sư AI: {@code ollama} (cục bộ, mặc định) | {@code api} (API kiểu OpenAI: DeepSeek, OpenRouter...). */
    @Value("${tutor.llm.provider:ollama}")
    private String tutorProvider;

    @Value("${tutor.llm.base-url:https://api.deepseek.com}")
    private String tutorBaseUrl;

    @Value("${tutor.llm.api-key:}")
    private String tutorApiKey;

    @Value("${tutor.llm.model:deepseek-chat}")
    private String tutorModel;

    /** {@code reasoning_effort} gửi kèm (none, low, high...); trống thì không gửi. Model suy luận mặc định suy nghĩ rất lâu. */
    @Value("${tutor.llm.reasoning-effort:none}")
    private String tutorReasoningEffort;

    public String getTutorReasoningEffort() { return tutorReasoningEffort == null ? "" : tutorReasoningEffort.trim(); }

    /** Gia sư gọi API ngoài thay vì Ollama; cần có key, thiếu key thì vẫn dùng Ollama. */
    public boolean isTutorApi() {
        return tutorProvider != null && "api".equalsIgnoreCase(tutorProvider.trim()) && tutorApiKey != null && !tutorApiKey.isBlank();
    }

    /** Địa chỉ {@code /chat/completions} của API gia sư, ví dụ {@code https://api.deepseek.com/chat/completions}. */
    public String getTutorChatUrl() { return tutorBaseUrl.trim().replaceAll("/+$", "") + "/chat/completions"; }
    public String getTutorApiKey() { return tutorApiKey == null ? "" : tutorApiKey.trim(); }
    public String getTutorModel() { return isTutorApi() ? tutorModel : ollamaModel; }

    public int getConnectTimeoutMs() { return Math.max(1_000, connectTimeoutMs); }
    public int getReadTimeoutMs() { return Math.max(1_000, readTimeoutMs); }
}
