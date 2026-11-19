package com.jlas.kaiwa.rag;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Lấy ngữ cảnh cho một câu hỏi: tra chính xác từ/kanji trong MySQL và tìm đoạn giáo trình gần nhất trong chỉ mục (rag_chunk).
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
        // Từ theo chủ đề của lộ trình, rồi mẫu ngữ pháp khớp đúng chữ, rồi các đoạn gần nghĩa.
        Map<String, Passage> passages = new java.util.LinkedHashMap<>();
        RagLexiconLookup.Topic topic = lexicon.topic(plain(question));
        if (topic != null) {
            passages.put("topic", new Passage("topic", 1.0, "VOCAB_TOPIC", topic.title(),
                    "Từ vựng " + topic.level() + " chủ đề " + topic.title() + ":\n" + String.join("\n", topic.lines()),
                    Map.of("level", topic.level())));
        }
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
        Map<String, Object> citation = new java.util.LinkedHashMap<>(
                payload.get("citation") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
        // Cấp JLPT của giáo trình, để gia sư trả lời được "từ N5 nào..." và nguồn hiển thị ghi cấp.
        if (payload.get("jlpt_level") != null && !citation.containsKey("level")) citation.put("jlpt_level", payload.get("jlpt_level"));
        String text = String.valueOf(payload.get("text"));
        if (citation.get("jlpt_level") != null) text = "(Cấp " + citation.get("jlpt_level") + ") " + text;
        return new Passage(hit.id(), hit.score(), String.valueOf(payload.get("source_type")), String.valueOf(payload.get("title")),
                text, citation);
    }

    private static String plain(String text) {
        return java.text.Normalizer.normalize(text.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd');
    }
}
