package com.jlas.kaiwa.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.config.OpenRouterConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Embedding cho RAG luôn chạy trên Ollama cục bộ ({@code /api/embed}), kể cả khi chat dùng OpenRouter:
 * nội dung giáo trình và câu hỏi của người học không bị gửi ra ngoài để embed.
 */
@Component
public class RagEmbedder {

    private final OpenRouterConfig config;
    private final ObjectMapper json;
    private final RestTemplate http;

    public RagEmbedder(OpenRouterConfig config, ObjectMapper json) {
        this.config = config;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        // Lần đầu phải nạp bge-m3 (đo được ~15 s), lô 16 đoạn nhanh hơn nhiều.
        factory.setReadTimeout(Math.max(30_000, config.getReadTimeoutMs()));
        this.http = new RestTemplate(factory);
    }

    public String model() {
        return config.getLocalEmbeddingModel();
    }

    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode vectors;
        try {
            String body = json.writeValueAsString(Map.of("model", model(), "input", texts, "keep_alive", config.getOllamaKeepAlive()));
            String raw = http.postForObject(config.getOllamaNativeUrl() + "/api/embed", new HttpEntity<>(body, headers), String.class);
            vectors = json.readTree(raw).path("embeddings");
        } catch (Exception e) {
            // Không đưa nội dung giáo trình, câu hỏi hay địa chỉ nội bộ vào thông báo lỗi.
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Không gọi được model embedding của Ollama.");
        }
        if (!vectors.isArray() || vectors.size() != texts.size())
            throw new IllegalStateException("Invalid embedding response");
        List<float[]> result = new ArrayList<>(texts.size());
        for (JsonNode vector : vectors) {
            if (!vector.isArray() || vector.isEmpty()) throw new IllegalStateException("Invalid embedding response");
            float[] values = new float[vector.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = (float) vector.get(i).asDouble();
                if (!Float.isFinite(values[i])) throw new IllegalStateException("Invalid embedding response");
            }
            result.add(values);
        }
        return result;
    }
}
