package com.jlas.learning.ai;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * [JLAS-W0108] Provider-selection semantics: DeepSeek is chosen when its key is non-blank, Gemini otherwise.
 * The bean selection happens once at startup (no per-call runtime fallback); this test pins that contract
 * without ever reading a real key — the field is injected with a dummy value via reflection.
 */
class AiProviderConfigTest {

    @Test
    void choosesDeepSeekWhenKeyIsNonBlank() {
        AiProviderConfig config = new AiProviderConfig();
        ReflectionTestUtils.setField(config, "deepseekKey", "dummy-not-a-real-key");
        GeminiClient gemini = new GeminiClient();
        DeepSeekClient deepseek = new DeepSeekClient();
        assertSame(deepseek, config.aiJsonGenerator(gemini, deepseek));
    }

    @Test
    void fallsBackToGeminiWhenKeyIsBlank() {
        AiProviderConfig config = new AiProviderConfig();
        ReflectionTestUtils.setField(config, "deepseekKey", "   ");
        GeminiClient gemini = new GeminiClient();
        DeepSeekClient deepseek = new DeepSeekClient();
        assertSame(gemini, config.aiJsonGenerator(gemini, deepseek));
    }
}
