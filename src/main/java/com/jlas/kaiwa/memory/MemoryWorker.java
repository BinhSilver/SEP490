package com.jlas.kaiwa.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static com.jlas.kaiwa.memory.MemoryTypes.*;

@Component
public class MemoryWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryWorker.class);
    private final MemoryStore store;
    private final MemoryAiClient ai;
    @Value("${kaiwa.memory.poll-ms:5000}") private long pollMs = 5000;
    private ScheduledExecutorService scheduler;
    public MemoryWorker(MemoryStore store, MemoryAiClient ai) { this.store = store; this.ai = ai; }

    @PostConstruct
    public void start() {
        // Private executor: registering a TaskScheduler bean would replace Boot's scheduler for other modules.
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "kaiwa-memory");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try { process(); }
            catch (RuntimeException e) {
                // Keep polling after a transient DB failure; durable leases make retry safe.
                log.warn("Memory worker poll failed ({})", e.getClass().getSimpleName());
            }
        }, 15_000, Math.max(1000, pollMs), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public void process() {
        if (!ai.available()) return;
        Job job = store.claim();
        if (job != null) {
            try {
                // Give the extractor stable existing keys. Include all personal facts, not historical summaries.
                List<Memory> known = store.memories(job.session().userId()).stream()
                        .filter(m -> !"SUMMARY".equals(m.kind())).sorted(java.util.Comparator.comparing(Memory::updatedAt).reversed()).limit(80).toList();
                Extraction extraction = ai.extract(job.session(), known);
                var embeddings = new HashMap<String, double[]>();
                // Persist text first. Embeddings are indexed independently below so outages don't lose facts.
                store.saveExtraction(job, extraction, embeddings, ai.embeddingModel());
            } catch (RuntimeException e) {
                store.fail(job);
                log.warn("Memory extraction retry queued for session {} ({})", job.session().id(), e.getClass().getSimpleName());
            }
        }
        for (Memory memory : store.missingEmbeddings(ai.embeddingModel())) {
            try { store.saveEmbedding(memory, ai.embed(memory.topic() + "\n" + memory.content()), ai.embeddingModel()); }
            catch (RuntimeException e) {
                store.failEmbedding(memory.id());
                log.warn("Memory vector indexing retry queued for memory {} ({})", memory.id(), e.getClass().getSimpleName());
                break; // Avoid repeated slow provider failures during a single poll.
            }
        }
    }
}
