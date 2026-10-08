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
        scheduler = Executors.newScheduledThreadPool(3, task -> {
            var thread = new Thread(task, "kaiwa-memory");
            thread.setDaemon(true);
            return thread;
        });
        // Claims use durable row leases / SKIP LOCKED, so two workers may safely
        // extract different sessions without one slow request blocking the queue.
        for (int worker = 0; worker < 2; worker++) scheduler.scheduleWithFixedDelay(() -> {
            try { extractPending(); }
            catch (RuntimeException e) {
                // Keep polling after a transient DB failure; durable leases make retry safe.
                log.warn("Memory worker poll failed ({})", e.getClass().getSimpleName());
            }
        }, 15_000, Math.max(1000, pollMs), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> {
            try { indexPending(); }
            catch (RuntimeException e) { log.warn("Memory index poll failed ({})", e.getClass().getSimpleName()); }
        }, 15_000, Math.max(1000, pollMs), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public void process() {
        extractPending();
        indexPending();
    }

    private void extractPending() {
        if (!ai.available()) return;
        Job job = store.claim();
        if (job != null) {
            try {
                // Give the extractor stable existing keys. Include all personal facts, not historical summaries.
                List<Memory> known = store.contextMemories(job.session().userId()).stream()
                        .filter(m -> !"SUMMARY".equals(m.kind())).sorted(java.util.Comparator.comparing(Memory::observedAt).reversed()).limit(80).toList();
                Extraction extraction = ai.extract(job.session(), known);
                var embeddings = new HashMap<String, double[]>();
                // Persist text first. Embeddings are indexed independently below so outages don't lose facts.
                store.saveExtraction(job, extraction, embeddings, ai.embeddingModel());
            } catch (RuntimeException e) {
                store.fail(job);
                log.warn("Memory extraction retry queued for session {} ({})", job.session().id(), e.getClass().getSimpleName());
            }
        }
    }

    private void indexPending() {
        if (!ai.available()) return;
        for (Memory memory : store.missingEmbeddings(ai.embeddingModel())) {
            try {
                // Index both the compact summary and the user's original language.
                // Cross-language paraphrases must not depend on summary language alone.
                store.saveEmbedding(memory, ai.embed(memory.topic() + "\n" + memory.content() + "\n" + memory.evidence()), ai.embeddingModel());
            }
            catch (RuntimeException e) {
                store.failEmbedding(memory.id());
                log.warn("Memory vector indexing retry queued for memory {} ({})", memory.id(), e.getClass().getSimpleName());
                break; // Avoid repeated slow provider failures during a single poll.
            }
        }
    }
}
