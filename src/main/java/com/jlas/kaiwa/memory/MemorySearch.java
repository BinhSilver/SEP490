package com.jlas.kaiwa.memory;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static com.jlas.kaiwa.memory.MemoryTypes.*;

@Service
public class MemorySearch {
    private static final Logger log = LoggerFactory.getLogger(MemorySearch.class);
    private final MemoryStore store;
    private final MemoryAiClient ai;
    private final MemoryProperties properties;
    // Query vectors embedded off the reply path, by memory session (MemoryContext.prefetchNext): an
    // embedding takes ~1.5 s through OpenRouter, too long to wait for before every reply.
    private final Map<Long, Prefetch> prefetched = new ConcurrentHashMap<>();
    private final ExecutorService prefetcher = Executors.newFixedThreadPool(2, task -> {
        var thread = new Thread(task, "kaiwa-memory-prefetch");
        thread.setDaemon(true);
        return thread;
    });

    private record Prefetch(String text, Future<double[]> vector) { }

    public MemorySearch(MemoryStore store, MemoryAiClient ai, MemoryProperties properties) {
        this.store = store; this.ai = ai; this.properties = properties;
    }

    public List<Hit> search(long userId, String query, int limit) {
        return search(userId, query, limit, null);
    }

    /** As search, with the semantic side taken from a vector embedded earlier; null embeds the query now. */
    public List<Hit> search(long userId, String query, int limit, double[] queryVector) {
        List<Memory> memories = store.contextMemories(userId);
        if (memories.isEmpty()) return List.of();
        double[] vector = queryVector;
        if (vector == null && !query.isBlank() && indexed(memories)) {
            try { vector = ai.embed(query); }
            catch (RuntimeException e) { log.warn("Memory embedding query unavailable; using keyword retrieval ({})", e.getClass().getSimpleName()); }
        }
        return rank(userId, memories, query, vector, ai.embeddingModel(), LocalDateTime.now(),
                properties.semanticThreshold(), Math.min(Math.max(1, limit), 20));
    }

    /** True when a memory has a vector of the current embedding model, so a search embeds its query. */
    public boolean indexed(List<Memory> memories) {
        return memories.stream().anyMatch(m -> ai.embeddingModel().equals(m.embeddingModel()) && m.embedding() != null);
    }

    /** Starts embedding the text the next search of this session will use, without waiting for it. */
    public void prefetch(long sessionId, String text) {
        if (!ai.available() || text.isBlank()) return;
        // One entry per active session; past the bound the next turns just embed their query themselves.
        if (prefetched.size() >= 1000) prefetched.clear();
        prefetched.put(sessionId, new Prefetch(text, CompletableFuture.supplyAsync(() -> ai.embed(text), prefetcher)));
    }

    /** The vector prefetched for exactly this text, waiting briefly for one still in flight; else null. */
    public double[] prefetched(long sessionId, String text) {
        Prefetch prefetch = prefetched.get(sessionId);
        if (prefetch == null || !prefetch.text().equals(text)) return null;
        try { return prefetch.vector().get(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        catch (ExecutionException | TimeoutException e) { return null; }
    }

    @PreDestroy
    public void stop() {
        prefetcher.shutdownNow();
    }

    static List<Hit> rank(long userId, List<Memory> memories, String query, double[] vector, String model,
                          LocalDateTime now, double threshold, int limit) {
        Set<String> words = words(query);
        List<Hit> hits = new ArrayList<>();
        for (Memory memory : memories) {
            if (memory.userId() != userId || (memory.expiresAt() != null && !memory.expiresAt().isAfter(now))) continue;
            // Preserve a keyword path in the user's original language while the
            // asynchronous vector index is pending or unavailable.
            Set<String> candidate = words(memory.topic() + " " + memory.content() + " " + memory.evidence());
            double keyword = words.isEmpty() ? 0 : (double) words.stream().filter(candidate::contains).count() / words.size();
            double semantic = model.equals(memory.embeddingModel()) ? cosine(vector, memory.embedding()) : 0;
            if (keyword == 0 && semantic < threshold) continue;
            // Age never excludes a relevant memory; importance/freshness only break close relevance scores.
            double score = keyword * 0.4 + Math.max(0, semantic) * 0.6 + memory.importance() * 0.005;
            hits.add(new Hit(memory, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h -> h.memory().observedAt(), Comparator.reverseOrder()).thenComparing(h -> h.memory().id()));
        return hits.stream().limit(limit).toList();
    }

    static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) return 0;
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < left.length; i++) {
            if (!Double.isFinite(left[i]) || !Double.isFinite(right[i])) return 0;
            dot += left[i] * right[i]; a += left[i] * left[i]; b += right[i] * right[i];
        }
        double result = a == 0 || b == 0 ? 0 : dot / (Math.sqrt(a) * Math.sqrt(b));
        return Double.isFinite(result) ? Math.max(-1, Math.min(1, result)) : 0;
    }

    public static Set<String> words(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd');
        Set<String> words = new HashSet<>();
        for (String word : normalized.split("[^\\p{L}\\p{N}]+")) if (!word.isBlank()) words.add(word);
        // Japanese is often unsegmented: character bigrams make keyword fallback useful without a tokenizer.
        for (String word : List.copyOf(words)) {
            if (word.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*") && word.length() > 2)
                for (int i = 0; i < word.length() - 1; i++) words.add(word.substring(i, i + 2));
        }
        words.removeAll(Set.of("です", "ます", "ですか", "すか", "しま", "ますか", "した", "ました", "は何", "何で", "につ", "つい", "いて", "私の", "私は", "前に", "いた", "いま", "ょう", "こと", "のこ", "てい", "して", "toi", "minh", "ban", "la", "va", "cua", "co", "khong", "thi", "nhe", "hom", "truoc", "the", "a", "an", "i", "you", "to", "is"));
        return words;
    }
}
