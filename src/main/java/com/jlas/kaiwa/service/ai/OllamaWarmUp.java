package com.jlas.kaiwa.service.ai;

import com.jlas.kaiwa.config.OpenRouterConfig;
import com.jlas.kaiwa.rag.RagProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Nạp model Ollama ngay khi app khởi động để người học đầu tiên không phải chờ lần nạp lạnh
 * (đo trên RTX 3070 Ti: ~20 s nạp, ~30 s cho token đầu). Chạy nền, lỗi chỉ ghi log, không chặn khởi động.
 */
@Component
public class OllamaWarmUp {

    private static final Logger log = LoggerFactory.getLogger(OllamaWarmUp.class);

    private final OpenRouterConfig config;
    private final OpenRouterClient client;
    private final LocalLlmClient localClient;
    private final RagProperties rag;

    public OllamaWarmUp(OpenRouterConfig config, OpenRouterClient client, LocalLlmClient localClient, RagProperties rag) {
        this.config = config;
        this.client = client;
        this.localClient = localClient;
        this.rag = rag;
    }

    /** Nạp model khi AI Call chạy Ollama, hoặc khi chatbot gia sư được bật (chatbot luôn dùng Ollama). */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!config.isOllamaWarmUp() || (!config.isLocal() && !rag.enabled())) return;
        Thread.ofVirtual().name("ollama-warm-up").start(() -> {
            if (config.isLocal()) {
                OpenRouterClient.ProbeResult result = client.probe();
                if (result.ok()) log.info("Ollama warm-up OK model={} latencyMs={}", result.model(), result.latencyMs());
                else log.warn("Ollama warm-up failed model={} error={}", result.model(), result.error());
                return;
            }
            long started = System.nanoTime();
            try {
                localClient.chat("Trả lời đúng một từ.", List.of(), "はい", 8);
                log.info("Ollama warm-up OK model={} latencyMs={}", localClient.model(), (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException e) {
                log.warn("Ollama warm-up failed model={} error={}", localClient.model(), e.getMessage());
            }
        });
    }
}
