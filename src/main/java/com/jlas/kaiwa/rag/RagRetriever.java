package com.jlas.kaiwa.rag;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Lấy ngữ cảnh cho một câu hỏi: tra chính xác từ/kanji trong MySQL và tìm đoạn giáo trình gần nhất trong Qdrant.
 * Không tìm thấy gì thì trả rỗng; không bao giờ tự lấp chỗ trống.
 */
@Service
public class RagRetriever {

    private final RagEmbedder embedder;
    private final RagVectorStore store;
    private final RagLexiconLookup lexicon;
    private final RagProperties properties;

    public RagRetriever(RagEmbedder embedder, RagVectorStore store, RagLexiconLookup lexicon, RagProperties properties) {
        this.embedder = embedder;
        this.store = store;
        this.lexicon = lexicon;
        this.properties = properties;
    }

    public record Passage(String id, double score, String sourceType, String title, String text, Map<String, Object> citation) { }

    public record Context(List<Passage> passages, RagLexiconLookup.Result lexicon) {
        public boolean isEmpty() { return passages.isEmpty() && lexicon.isEmpty(); }
    }

    public Context retrieve(String question, Integer limit) {
        return retrieve(question, limit, null);
    }

    /** @param minScore null = ngưỡng cấu hình; admin hạ ngưỡng để hiệu chỉnh trên model thật */
    public Context retrieve(String question, Integer limit, Double minScore) {
        RagIndexService.requireEnabled(properties);
        double threshold = minScore == null ? properties.minScore() : Math.clamp(minScore, 0.0, 1.0);
        int k = limit == null ? properties.topK() : Math.clamp(limit, 1, 12);
        float[] vector = embedder.embed(List.of(question)).getFirst();
        String collection = properties.collection(embedder.model());
        List<Passage> passages = store.dimension(collection).isEmpty() ? List.of()
                : store.search(collection, vector, k, threshold).stream().map(RagRetriever::passage).toList();
        return new Context(passages, lexicon.lookup(question));
    }

    @SuppressWarnings("unchecked")
    private static Passage passage(RagVectorStore.Hit hit) {
        Map<String, Object> payload = hit.payload();
        Object citation = payload.get("citation");
        return new Passage(hit.id(), hit.score(), String.valueOf(payload.get("source_type")), String.valueOf(payload.get("title")),
                String.valueOf(payload.get("text")), citation instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
    }
}
