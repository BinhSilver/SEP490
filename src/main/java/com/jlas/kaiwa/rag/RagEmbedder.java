package com.jlas.kaiwa.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.ai.AiFeature;
import com.jlas.common.ai.AiUsageRecorder;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.config.OpenRouterConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Embedding cho RAG qua OpenRouter ({@code /embeddings}, model {@code kaiwa.rag.embedding-model}, mặc định bge-m3).
 * Dùng key OpenRouter của tính năng {@link AiFeature#TUTOR_SEARCH} (hoặc key chung) và ghi chi phí vào ai_usage_log.
 */
@Component
public class RagEmbedder {

    private final OpenRouterConfig openRouter;
    private final RagProperties properties;
    private final ObjectMapper json;
    private final RestTemplate http;
    @Autowired(required = false) private AiUsageRecorder usage;

    public RagEmbedder(OpenRouterConfig openRouter, RagProperties properties, ObjectMapper json) {
        this.openRouter = openRouter;
        this.properties = properties;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(properties.timeoutMs());
        this.http = new RestTemplate(factory);
    }

    public String model() {
        return properties.embeddingModel();
    }

    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(AiFeature.TUTOR_SEARCH.apiKey(openRouter.getApiKey()));
        JsonNode data;
        try {
            String body = json.writeValueAsString(Map.of("model", model(), "input", texts, "encoding_format", "float"));
            String url = openRouter.getApiUrl().replaceAll("/+$", "").replaceAll("/chat/completions$", "") + "/embeddings";
            String raw = http.postForObject(url, new HttpEntity<>(body, headers), String.class);
            if (usage != null) usage.recordOpenRouter(AiFeature.TUTOR_SEARCH, model(), raw);
            data = json.readTree(raw).path("data");
        } catch (Exception e) {
            // Không đưa nội dung giáo trình, câu hỏi hay key vào thông báo lỗi.
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Không gọi được model tìm kiếm của gia sư AI.");
        }
        if (!data.isArray() || data.size() != texts.size()) throw new IllegalStateException("Invalid embedding response");
        List<float[]> result = new ArrayList<>(texts.size());
        for (JsonNode item : data) {
            JsonNode vector = item.path("embedding");
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
