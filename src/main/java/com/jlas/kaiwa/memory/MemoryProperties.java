package com.jlas.kaiwa.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kaiwa.memory")
public record MemoryProperties(String embeddingModel, int searchLimit, int contextChars,
                               int recentMessages, int checkpointTurns, double semanticThreshold) {
    public MemoryProperties {
        if (embeddingModel == null || embeddingModel.isBlank()) embeddingModel = "openai/text-embedding-3-small";
        if (searchLimit <= 0) searchLimit = 8;
        searchLimit = Math.min(searchLimit, 20);
        if (contextChars <= 0) contextChars = 6000;
        contextChars = Math.min(contextChars, 12000);
        if (recentMessages <= 0) recentMessages = 16;
        recentMessages = Math.min(recentMessages, 32);
        if (checkpointTurns <= 0) checkpointTurns = 6;
        if (semanticThreshold <= 0 || semanticThreshold >= 1) semanticThreshold = 0.35;
    }
}
