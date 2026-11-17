package com.jlas.kaiwa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG cho gia sư AI: nội dung giáo trình J-LAS (MySQL là nguồn gốc) được embed qua OpenRouter và lưu vector trong
 * bảng {@code rag_chunk} (migration 084); app tìm trong bộ nhớ.
 *
 * @param enabled             tắt mặc định
 * @param embeddingModel      model embedding trên OpenRouter, ví dụ {@code baai/bge-m3}
 * @param collectionPrefix    tên bộ chỉ mục = prefix + model embedding, đổi model thì sang bộ mới (không trộn vector)
 * @param batchSize           số đoạn embed mỗi lần gọi
 * @param topK                số đoạn giáo trình đưa vào prompt
 * @param minScore            điểm cosine tối thiểu; thấp hơn coi như không có nguồn
 * @param includeVnjpclub     nội dung vnjpclub chưa có giấy phép phân phối lại: mặc định không index
 * @param includeUnreviewed   nội dung AI_GENERATED chưa duyệt (N3–N1): mặc định không index
 * @param timeoutMs           timeout gọi model embedding
 */
@ConfigurationProperties(prefix = "kaiwa.rag")
public record RagProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("baai/bge-m3") String embeddingModel,
        @DefaultValue("jlas_content") String collectionPrefix,
        @DefaultValue("64") int batchSize,
        @DefaultValue("5") int topK,
        @DefaultValue("0.50") double minScore,
        @DefaultValue("false") boolean includeVnjpclub,
        @DefaultValue("false") boolean includeUnreviewed,
        @DefaultValue("30000") int timeoutMs) {

    public RagProperties {
        embeddingModel = embeddingModel == null || embeddingModel.isBlank() ? "baai/bge-m3" : embeddingModel.trim();
        batchSize = Math.clamp(batchSize, 1, 64);
        topK = Math.clamp(topK, 1, 12);
        timeoutMs = Math.max(1_000, timeoutMs);
    }

    /** Tên bộ chỉ mục theo model embedding, ví dụ {@code jlas_content_baai_bge_m3}. */
    public String collection(String embeddingModel) {
        return collectionPrefix + "_" + embeddingModel.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }
}
