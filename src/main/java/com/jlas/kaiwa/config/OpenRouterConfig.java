package com.jlas.kaiwa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenRouterConfig {

    @Value("${openrouter.api.key}")
    private String apiKey;

    @Value("${openrouter.api.url}")
    private String apiUrl;

    @Value("${openrouter.site.url}")
    private String siteUrl;

    @Value("${openrouter.site.name}")
    private String siteName;

    @Value("${openrouter.model:qwen/qwen3-14b}")
    private String model;

    // Reasoning effort of the conversation calls (none, minimal, low, ...). "off" sends no reasoning
    // parameter at all: an Anthropic model starts thinking as soon as any effort is given.
    @Value("${openrouter.reasoning-effort:low}")
    private String reasoningEffort;

    // OpenRouter provider sort (latency, throughput, price); empty keeps OpenRouter's own routing.
    @Value("${openrouter.provider-sort:}")
    private String providerSort;

    public String getApiKey() { return apiKey; }
    public String getApiUrl() { return apiUrl; }
    public String getSiteUrl() { return siteUrl; }
    public String getSiteName() { return siteName; }
    public String getModel() { return model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public String getProviderSort() { return providerSort; }
}
