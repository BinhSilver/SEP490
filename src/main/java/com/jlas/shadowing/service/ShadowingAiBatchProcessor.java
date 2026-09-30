package com.jlas.shadowing.service;

import com.jlas.shadowing.model.Subtitle;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded, shared AI concurrency across upload and YouTube import jobs. */
@Component
public class ShadowingAiBatchProcessor {
    private static final Logger log = LoggerFactory.getLogger(ShadowingAiBatchProcessor.class);
    private final OpenRouterService ai;
    private final ThreadPoolExecutor workers;
    private final int parallelism;

    public ShadowingAiBatchProcessor(OpenRouterService ai,
            @Value("${processing.ai.parallelism:3}") int parallelism) {
        this.ai = ai;
        this.parallelism = Math.max(1, Math.min(4, parallelism));
        this.workers = new ThreadPoolExecutor(this.parallelism, this.parallelism,
                30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void process(List<Subtitle> subtitles, int batchSize, String language, boolean furigana,
                        boolean reuseTranslation, BooleanSupplier active,
                        BatchHandler handler) {
        long started = System.nanoTime();
        int size = Math.max(1, Math.min(16, batchSize));
        List<List<Subtitle>> chunks = new ArrayList<>();
        for (int offset = 0; offset < subtitles.size(); offset += size) {
            chunks.add(subtitles.subList(offset, Math.min(subtitles.size(), offset + size)));
        }
        for (int start = 0; start < chunks.size(); start += parallelism) {
            if (!active.getAsBoolean()) return;
            int end = Math.min(chunks.size(), start + parallelism);
            List<CompletableFuture<List<Map<String, String>>>> futures = new ArrayList<>();
            for (int index = start; index < end; index++) {
                List<String> texts = chunks.get(index).stream().map(Subtitle::getText).toList();
                List<Subtitle> chunk = chunks.get(index);
                futures.add(CompletableFuture.supplyAsync(() ->
                        !active.getAsBoolean() ? List.of() :
                        reuseTranslation && chunk.stream().allMatch(subtitle -> subtitle.getTranslation() != null
                                && !subtitle.getTranslation().isBlank())
                                ? ai.processFuriganaOnlyBatch(texts,
                                        chunk.stream().map(Subtitle::getTranslation).toList(), language)
                                : ai.processJapaneseBatch(texts, language, furigana), workers));
            }
            for (int index = start; index < end; index++) {
                if (!active.getAsBoolean()) return;
                handler.handle(chunks.get(index), futures.get(index - start).join(),
                        Math.min(subtitles.size(), (index + 1) * size));
            }
        }
        log.info("Shadowing AI stage completed: lines={}, batches={}, parallelism={}, durationMs={}",
                subtitles.size(), chunks.size(), parallelism,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    @FunctionalInterface
    public interface BatchHandler {
        void handle(List<Subtitle> batch, List<Map<String, String>> result, int processedCount);
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdown();
    }
}
