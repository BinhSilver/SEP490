package com.jlas.learning.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * [JLAS-W0108] Chọn provider AI theo key đang bật trong {@code application-local.yaml} — user chỉ cần
 * comment/uncomment key, không cần sửa code:
 *
 * <ul>
 *   <li>{@code deepseek.api.key} có giá trị → dùng {@link DeepSeekClient};</li>
 *   <li>ngược lại → dùng {@link GeminiClient} (tự trả {@code null} khi {@code gemini-api-key} trống).</li>
 * </ul>
 */
@Configuration
public class AiProviderConfig {

    @Value("${deepseek.api.key:${DEEPSEEK_API_KEY:}}")
    private String deepseekKey;

    @Bean
    @Primary
    public AiJsonGenerator aiJsonGenerator(GeminiClient gemini, DeepSeekClient deepseek) {
        if (deepseekKey != null && !deepseekKey.isBlank()) {
            return deepseek;
        }
        return gemini;
    }
}
