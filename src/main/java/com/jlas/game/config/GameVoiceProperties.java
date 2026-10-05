package com.jlas.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** LiveKit Cloud credentials are server-side only. Voice is opt-in and disabled without configuration. */
@ConfigurationProperties(prefix = "game.voice")
public record GameVoiceProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String url,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String apiSecret,
        @DefaultValue("") String allowedOrigins,
        @DefaultValue("60") int tokenTtlSeconds,
        @DefaultValue("10") int tokensPerMinute) {
}
