package com.jlas.kaiwa.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Đồng bộ Qdrant theo MySQL. Mỗi lần chạy so content_hash của từng điểm: chỉ embed đoạn mới hoặc đổi,
 * xoá điểm không còn trong MySQL, giữ nguyên phần còn lại. Chạy lại khi không có gì đổi thì không embed gì.
 */
@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);

    private final RagContentSource source;
    private final RagEmbedder embedder;
    private final RagVectorStore store;
    private final RagProperties properties;
    private final ObjectMapper json;
    private final ReentrantLock running = new ReentrantLock();

    public RagIndexService(RagContentSource source, RagEmbedder embedder, RagVectorStore store,
                           RagProperties properties, ObjectMapper json) {
        this.source = source;
        this.embedder = embedder;
        this.store = store;
        this.properties = properties;
        this.json = json;
    }

    public record Report(String collection, String embeddingModel, Long lessonId, int chunks, int embedded,
                         int unchanged, int deleted, long durationMs) { }

    /** @param qdrantReachable null khi RAG tắt (không gọi Qdrant) */
    public record Status(boolean enabled, Boolean qdrantReachable, String collection, String embeddingModel, Long points,
                         boolean includeVnjpclub, boolean includeUnreviewed) { }

    public String collection() {
        return properties.collection(embedder.model());
    }

    public Status status() {
        Long points = null;
        Boolean reachable = null;
        if (properties.enabled()) {
            try {
                if (store.dimension(collection()).isPresent()) points = store.count(collection());
                reachable = true;
            } catch (RuntimeException e) {
                reachable = false;
            }
        }
        return new Status(properties.enabled(), reachable, collection(), embedder.model(), points,
                properties.includeVnjpclub(), properties.includeUnreviewed());
    }

    /** @param lessonId null = toàn bộ nội dung; có giá trị = chỉ đồng bộ một bài (sau khi sửa bài đó). */
    public Report reindex(Long lessonId) {
        requireEnabled(properties);
        if (!running.tryLock()) throw new ApiException(ErrorCode.CONFLICT, "Đang có một lượt reindex khác chạy.");
        long started = System.nanoTime();
        try {
            String collection = collection();
            List<RagChunk> chunks = source.load(lessonId);
            Map<String, RagChunk> desired = new LinkedHashMap<>();
            Map<String, String> desiredHash = new LinkedHashMap<>();
            for (RagChunk chunk : chunks) {
                if (desired.put(chunk.pointId(), chunk) != null)
                    throw new IllegalStateException("Duplicate RAG chunk key " + chunk.key());
                desiredHash.put(chunk.pointId(), chunk.contentHash(citationJson(chunk)));
            }

            Map<String, String> existing = ensureCollection(collection, chunks) ? store.hashes(collection, lessonId) : Map.of();
            List<RagChunk> changed = new ArrayList<>();
            desired.forEach((id, chunk) -> {
                if (!desiredHash.get(id).equals(existing.get(id))) changed.add(chunk);
            });
            Set<String> stale = new HashSet<>(existing.keySet());
            stale.removeAll(desired.keySet());

            for (int from = 0; from < changed.size(); from += properties.batchSize()) {
                List<RagChunk> batch = changed.subList(from, Math.min(changed.size(), from + properties.batchSize()));
                List<float[]> vectors = embedder.embed(batch.stream().map(RagChunk::text).toList());
                List<RagVectorStore.Point> points = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    RagChunk chunk = batch.get(i);
                    points.add(new RagVectorStore.Point(chunk.pointId(), vectors.get(i), payload(chunk, desiredHash.get(chunk.pointId()))));
                }
                store.upsert(collection, points);
            }
            store.delete(collection, stale);

            Report report = new Report(collection, embedder.model(), lessonId, chunks.size(), changed.size(),
                    chunks.size() - changed.size(), stale.size(), (System.nanoTime() - started) / 1_000_000);
            log.info("RAG reindex {}", report);
            return report;
        } finally {
            running.unlock();
        }
    }

    static void requireEnabled(RagProperties properties) {
        if (!properties.enabled()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "RAG chưa được bật (kaiwa.rag.enabled).");
    }

    /** @return true nếu collection đã có từ trước (cần so hash), false nếu vừa tạo hoặc không có gì để tạo. */
    private boolean ensureCollection(String collection, List<RagChunk> chunks) {
        var dimension = store.dimension(collection);
        if (dimension.isPresent()) return true;
        if (chunks.isEmpty()) return false;
        int size = embedder.embed(List.of(chunks.getFirst().text())).getFirst().length;
        store.createCollection(collection, size);
        return false;
    }

    private Map<String, Object> payload(RagChunk chunk, String hash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", chunk.key());
        payload.put("source_type", chunk.sourceType());
        payload.put("source_id", chunk.sourceId());
        payload.put("lesson_id", chunk.lessonId());
        payload.put("textbook_id", chunk.textbookId());
        payload.put("jlpt_level", chunk.jlptLevel());
        payload.put("title", chunk.title());
        payload.put("pattern_key", RagChunk.patternKey(chunk.title()));
        payload.put("text", chunk.text());
        payload.put("citation", chunk.citation());
        payload.put("content_hash", hash);
        payload.put("index_version", RagChunk.INDEX_VERSION);
        return payload;
    }

    private String citationJson(RagChunk chunk) {
        try {
            return json.writeValueAsString(chunk.citation());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
