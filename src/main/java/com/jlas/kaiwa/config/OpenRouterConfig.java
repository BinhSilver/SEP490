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

    public String getApiKey() { return apiKey; }
    public String getApiUrl() { return apiUrl; }
    public String getSiteUrl() { return siteUrl; }
    public String getSiteName() { return siteName; }
    public String getModel() { return model; }
}
