package com.jlas.common.ai;

import com.jlas.auth.security.CurrentUser;
import com.jlas.auth.security.UserPrincipal;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ghi chi phí thật của mỗi lần gọi AI vào ai_usage_log (kế hoạch Pro/CP, mục 2 "Quy trình chốt giá", bước Đo):
 * token, ký tự giọng đọc, giây âm thanh và chi phí USD khi nhà cung cấp trả về. Dùng để tính chi phí trung bình
 * và P90 mỗi chức năng rồi chỉnh giá CP. Không bao giờ làm hỏng lượt gọi AI: lỗi ghi chỉ được log.
 */
@Component
public class AiUsageRecorder {
    private static final Logger log = LoggerFactory.getLogger(AiUsageRecorder.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final JdbcTemplate jdbc;

    public AiUsageRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One call. Counts that do not apply stay null; {@code userId} null falls back to the signed-in user. */
    public record Usage(Long userId, String feature, String provider, String model, Integer promptTokens,
                        Integer completionTokens, Integer reasoningTokens, Integer cachedTokens, Integer characters,
                        BigDecimal audioSeconds, BigDecimal costUsd) {
    }

    public void record(Usage usage) {
        try {
            Long userId = usage.userId() != null ? usage.userId()
                    : CurrentUser.current().map(UserPrincipal::userId).orElse(null);
            jdbc.update("INSERT INTO ai_usage_log (user_id, feature, provider, model, prompt_tokens, completion_tokens, "
                            + "reasoning_tokens, cached_tokens, characters, audio_seconds, cost_usd, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
                    userId, usage.feature(), usage.provider(), clip(usage.model()), usage.promptTokens(),
                    usage.completionTokens(), usage.reasoningTokens(), usage.cachedTokens(), usage.characters(),
                    usage.audioSeconds(), usage.costUsd());
        } catch (RuntimeException failure) {
            log.warn("AI usage not recorded feature={} model={}: {}", usage.feature(), usage.model(), failure.getMessage());
        }
    }

    /**
     * Reads OpenRouter's {@code usage} block (prompt/completion tokens, reasoning and cached token details, cost in
     * USD) from a parsed chat-completions response; missing fields stay null.
     */
    public void recordOpenRouter(String feature, String model, Map<?, ?> response) {
        Map<?, ?> usage = response == null ? null : map(response.get("usage"));
        if (usage == null) {
            record(new Usage(null, feature, "openrouter", model, null, null, null, null, null, null, null));
            return;
        }
        Map<?, ?> completion = map(usage.get("completion_tokens_details"));
        Map<?, ?> prompt = map(usage.get("prompt_tokens_details"));
        record(new Usage(null, feature, "openrouter", model, integer(usage.get("prompt_tokens")),
                integer(usage.get("completion_tokens")),
                completion == null ? null : integer(completion.get("reasoning_tokens")),
                prompt == null ? null : integer(prompt.get("cached_tokens")),
                null, null, decimal(usage.get("cost"))));
    }

    public void recordOpenRouter(AiFeature feature, String model, Map<?, ?> response) {
        recordOpenRouter(feature.name(), model, response);
    }

    /** Same as the Map version, from the raw JSON body; a body that is not JSON is logged without counts. */
    public void recordOpenRouter(AiFeature feature, String model, String rawResponse) {
        Map<?, ?> response = null;
        try {
            response = rawResponse == null ? null : JSON.readValue(rawResponse, Map.class);
        } catch (Exception ignored) {
            // still counted as a call with an unknown cost
        }
        recordOpenRouter(feature.name(), model, response);
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> found ? found : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }

    private static String clip(String model) {
        return model == null || model.length() <= 120 ? model : model.substring(0, 120);
    }
}
