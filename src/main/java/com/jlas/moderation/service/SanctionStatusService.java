package com.jlas.moderation.service;

import com.jlas.moderation.entity.UserSanction;
import com.jlas.moderation.repository.UserSanctionRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Answers "is this account banned right now?" on every authenticated request.
 * Results are cached briefly and dropped as soon as a sanction changes, so a new ban
 * applies on the user's next request without querying the database each time.
 */
@Service
@RequiredArgsConstructor
public class SanctionStatusService {
    static final long TTL_MILLIS = 30_000;
    private static final int MAX_ENTRIES = 10_000;

    private final UserSanctionRepository sanctions;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    /** What a banned user is told; the full list is available from /api/appeals/sanctions. */
    public record ActiveBan(Long sanctionId, LocalDateTime endsAt, String reason) {
    }

    private record Cached(Optional<ActiveBan> ban, long expiresAt) {
    }

    @Transactional(readOnly = true)
    public Optional<ActiveBan> activeBan(Long userId) {
        if (userId == null) return Optional.empty();
        long now = System.currentTimeMillis();
        Cached cached = cache.get(userId);
        if (cached != null && cached.expiresAt() > now) {
            Optional<ActiveBan> ban = cached.ban();
            // A cached ban may have expired on its own since it was read.
            if (ban.isEmpty() || ban.get().endsAt() == null || ban.get().endsAt().isAfter(LocalDateTime.now())) {
                return ban;
            }
        }
        Optional<ActiveBan> ban = sanctions.findActiveByUserIdAndType(userId, "BAN", LocalDateTime.now())
                .map(SanctionStatusService::view);
        if (cache.size() >= MAX_ENTRIES) cache.clear();
        cache.put(userId, new Cached(ban, now + TTL_MILLIS));
        return ban;
    }

    /** Drops the cached status once the surrounding transaction commits (or right away without one). */
    public void invalidate(Long userId) {
        if (userId == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.remove(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.remove(userId);
            }
        });
    }

    private static ActiveBan view(UserSanction sanction) {
        return new ActiveBan(sanction.getId(), sanction.getEndsAt(), sanction.getReason());
    }
}
