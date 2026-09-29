package com.jlas.kaiwa.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Default local implementation. The interface keeps the application
 * independent from a specific cache vendor and allows a Redis implementation
 * to be introduced when multiple application instances are deployed.
 */
@Component
public class InMemoryConversationRuntimeStore implements ConversationRuntimeStore {

    private final Cache<Long, ConversationRuntimeState> cache;

    public InMemoryConversationRuntimeStore(
            @Value("${aicall.runtime.ttl-minutes:120}") long ttlMinutes,
            @Value("${aicall.runtime.max-sessions:10000}") long maxSessions) {
        long safeTtl = Math.max(1, ttlMinutes);
        long safeMaximum = Math.max(100, maxSessions);
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(safeTtl))
                .maximumSize(safeMaximum)
                .build();
    }

    @Override
    public Optional<ConversationRuntimeState> get(Long sessionId) {
        return Optional.ofNullable(cache.getIfPresent(sessionId));
    }

    @Override
    public void put(ConversationRuntimeState state) {
        if (state != null && state.sessionId() != null) cache.put(state.sessionId(), state);
    }

    @Override
    public void remove(Long sessionId) {
        if (sessionId != null) cache.invalidate(sessionId);
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }
}
