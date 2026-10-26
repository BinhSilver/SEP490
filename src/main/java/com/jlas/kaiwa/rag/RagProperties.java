package com.jlas.kaiwa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG cho gia sư AI: nội dung giáo trình J-LAS (MySQL là nguồn gốc) được index vào Qdrant bằng embedding Ollama.
 *
 * @param enabled             tắt mặc định; bật khi máy chủ AI có Qdrant và model embedding
 * @param qdrantUrl           chỉ nên là địa chỉ loopback/LAN nội bộ, không mở ra Internet
 * @param qdrantApiKey        để trống khi Qdrant chỉ nghe trên 127.0.0.1
 * @param collectionPrefix    tên collection = prefix + model embedding, đổi model thì sang collection mới
 * @param batchSize           số đoạn embed mỗi lần gọi Ollama
 * @param topK                số đoạn giáo trình đưa vào prompt
 * @param minScore            điểm cosine tối thiểu; thấp hơn coi như không có nguồn
 * @param includeVnjpclub     nội dung vnjpclub chưa có giấy phép phân phối lại: mặc định không index
 * @param includeUnreviewed   nội dung AI_GENERATED chưa duyệt (N3–N1): mặc định không index
 * @param timeoutMs           timeout gọi Qdrant
 */
@ConfigurationProperties(prefix = "kaiwa.rag")
public record RagProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://127.0.0.1:6333") String qdrantUrl,
        @DefaultValue("") String qdrantApiKey,
        @DefaultValue("jlas_content") String collectionPrefix,
        @DefaultValue("16") int batchSize,
        @DefaultValue("5") int topK,
        @DefaultValue("0.45") double minScore,
        @DefaultValue("false") boolean includeVnjpclub,
        @DefaultValue("false") boolean includeUnreviewed,
        @DefaultValue("30000") int timeoutMs) {

    public RagProperties {
        qdrantUrl = qdrantUrl == null ? "" : qdrantUrl.trim().replaceAll("/+$", "");
        batchSize = Math.clamp(batchSize, 1, 64);
        topK = Math.clamp(topK, 1, 12);
        timeoutMs = Math.max(1_000, timeoutMs);
    }

    /** Tên collection theo model embedding, ví dụ {@code jlas_content_bge_m3}. */
    public String collection(String embeddingModel) {
        return collectionPrefix + "_" + embeddingModel.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }
}
