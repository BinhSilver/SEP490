package com.jlas.shadowing.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.regex.Pattern;

@Configuration
public class AzureSpeechPronunciationConfig {
    private static final Pattern REGION_PATTERN = Pattern.compile("[a-z0-9-]{2,32}");

    private final String subscriptionKey;
    private final String region;
    private final String locale;
    private final long maxAudioBytes;
    private final int maxAudioSeconds;
    private final int requestsPerMinute;

    public AzureSpeechPronunciationConfig(
            @Value("${azure.speech.pronunciation.key:}") String subscriptionKey,
            @Value("${azure.speech.pronunciation.region:}") String region,
            @Value("${azure.speech.pronunciation.locale:ja-JP}") String locale,
            @Value("${azure.speech.pronunciation.max-audio-bytes:5242880}") long maxAudioBytes,
            @Value("${azure.speech.pronunciation.max-audio-seconds:30}") int maxAudioSeconds,
            @Value("${azure.speech.pronunciation.requests-per-minute:20}") int requestsPerMinute) {
        this.subscriptionKey = subscriptionKey == null ? "" : subscriptionKey.trim();
        this.region = region == null ? "" : region.trim().toLowerCase();
        this.locale = locale == null || locale.isBlank() ? "ja-JP" : locale.trim();
        this.maxAudioBytes = maxAudioBytes;
        this.maxAudioSeconds = maxAudioSeconds;
        this.requestsPerMinute = requestsPerMinute;
    }

    public boolean isConfigured() {
        return !subscriptionKey.isBlank() && REGION_PATTERN.matcher(region).matches();
    }

    public String getSubscriptionKey() { return subscriptionKey; }
    public String getRegion() { return region; }
    public String getLocale() { return locale; }
    public long getMaxAudioBytes() { return maxAudioBytes; }
    public int getMaxAudioSeconds() { return maxAudioSeconds; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
}
