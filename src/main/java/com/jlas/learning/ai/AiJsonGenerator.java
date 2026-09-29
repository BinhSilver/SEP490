package com.jlas.learning.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * [JLAS-W0108] Hợp đồng gọi provider AI sinh JSON (narrative lộ trình / câu quiz bù).
 *
 * <p>Mọi implementation là <b>best-effort</b>: trả {@code null} khi thiếu key / lỗi / rate-limit
 * để luồng gọi luôn có fallback không-AI. Provider được chọn theo key đang bật trong
 * {@code application-local.yaml} (xem {@link AiProviderConfig}).</p>
 */
public interface AiJsonGenerator {

    /**
     * Gửi prompt và trả về JSON đã strip code-fence + repair (object hoặc array).
     *
     * @return {@link JsonNode} hợp lệ, hoặc {@code null} nếu provider không dùng được (best-effort).
     */
    JsonNode generateJson(String systemPrompt, String userPrompt);
}
