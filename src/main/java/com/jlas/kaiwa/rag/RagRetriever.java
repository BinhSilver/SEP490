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

    /** Các mục từ điển khớp đúng chữ trong {@code text}; dùng để kiểm chứng một từ do model đề xuất. */
    public List<RagLexiconLookup.WordEntry> words(String text) {
        return lexicon.lookup(text).words();
    }

    /** @param minScore null = ngưỡng cấu hình; admin hạ ngưỡng để hiệu chỉnh trên model thật */
    public Context retrieve(String question, Integer limit, Double minScore) {
        RagIndexService.requireEnabled(properties);
        double threshold = minScore == null ? properties.minScore() : Math.clamp(minScore, 0.0, 1.0);
        int k = limit == null ? properties.topK() : Math.clamp(limit, 1, 12);
        float[] vector = embedder.embed(List.of(question)).getFirst();
        String collection = properties.collection(embedder.model());
        if (store.dimension(collection).isEmpty()) return new Context(List.of(), lexicon.lookup(question));
        // Mẫu ngữ pháp khớp đúng chữ đứng trước, rồi tới các đoạn gần nghĩa.
        Map<String, Passage> passages = new java.util.LinkedHashMap<>();
        exactPatterns(collection, question).forEach(hit -> passages.putIfAbsent(hit.id(), passage(hit)));
        store.search(collection, vector, k, threshold).forEach(hit -> passages.putIfAbsent(hit.id(), passage(hit)));
        return new Context(List.copyOf(passages.values()), lexicon.lookup(question));
    }

    static final int MAX_EXACT = 3;
    private static final java.util.regex.Pattern JAPANESE_RUN =
            java.util.regex.Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々]{2,}");

    /**
     * Mỗi cụm chữ Nhật trong câu hỏi được tìm trong tên mẫu; ưu tiên tên ngắn nhất (sát cụm hỏi nhất).
     * ponytail: chỉ xét 64 đoạn đầu cho mỗi cụm; cụm quá phổ biến (ない) có thể sót mẫu, khi cần thì thêm full-text index.
     */
    private List<RagVectorStore.Hit> exactPatterns(String collection, String question) {
        List<RagVectorStore.Hit> hits = new java.util.ArrayList<>();
        java.util.regex.Matcher run = JAPANESE_RUN.matcher(question);
        for (int runs = 0; run.find() && runs < 4; runs++) {
            hits.addAll(store.byPattern(collection, run.group(), 64).stream()
                    .sorted(java.util.Comparator.comparingInt(h -> String.valueOf(h.payload().get("pattern_key")).length()))
                    .limit(MAX_EXACT).toList());
        }
        return hits.stream().limit(MAX_EXACT * 2L).toList();
    }

    @SuppressWarnings("unchecked")
    private static Passage passage(RagVectorStore.Hit hit) {
        Map<String, Object> payload = hit.payload();
        Object citation = payload.get("citation");
        return new Passage(hit.id(), hit.score(), String.valueOf(payload.get("source_type")), String.valueOf(payload.get("title")),
                String.valueOf(payload.get("text")), citation instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
    }
}
