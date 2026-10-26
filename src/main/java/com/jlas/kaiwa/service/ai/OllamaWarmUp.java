package com.jlas.kaiwa.service.ai;

import com.jlas.kaiwa.config.OpenRouterConfig;
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

    public OllamaWarmUp(OpenRouterConfig config, OpenRouterClient client) {
        this.config = config;
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!config.isLocal() || !config.isOllamaWarmUp()) return;
        Thread.ofVirtual().name("ollama-warm-up").start(() -> {
            OpenRouterClient.ProbeResult result = client.probe();
            if (result.ok()) log.info("Ollama warm-up OK model={} latencyMs={}", result.model(), result.latencyMs());
            else log.warn("Ollama warm-up failed model={} error={}", result.model(), result.error());
        });
    }
}
