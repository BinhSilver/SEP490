package com.jlas.dictionary.kanjimap;

import com.jlas.auth.service.PremiumAccess;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.security.ContentReviewAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Server-side visible-use clock for the public Kanji map. */
@Service
public class KanjiMapQuota {
    static final int DAILY_SECONDS = 600;
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final PremiumAccess premium;
    private final Clock clock;
    // ponytail: per-user and per-IP map time resets when the server restarts.
    private final ConcurrentHashMap<String, Usage> usage = new ConcurrentHashMap<>();

    private record Usage(LocalDate day, int seconds, Instant lastBeat) { }
    public record Remaining(boolean unlimited, int remainingSeconds) { }

    @Autowired
    public KanjiMapQuota(PremiumAccess premium) { this(premium, Clock.systemUTC()); }

    KanjiMapQuota(PremiumAccess premium, Clock clock) {
        this.premium = premium;
        this.clock = clock;
    }

    private boolean fullAccess(Long userId) {
        return userId != null && (ContentReviewAccess.allowed(userId) || premium.isActive(userId));
    }

    private String key(Long userId, String ip) {
        return userId == null ? "ip:" + ip : "user:" + userId;
    }

    /** active=false closes the visible interval, so hidden-tab time is not charged. */
    public Remaining heartbeat(Long userId, String ip, boolean active) {
        if (fullAccess(userId)) return new Remaining(true, DAILY_SECONDS);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, VIETNAM);
        Usage current = usage.compute(key(userId, ip), (key, previous) -> {
            if (previous == null || !today.equals(previous.day())) return new Usage(today, 0, active ? now : null);
            int elapsed = previous.lastBeat() == null ? 0 : (int) Math.max(0,
                    Math.min(60, java.time.Duration.between(previous.lastBeat(), now).getSeconds()));
            int seconds = Math.min(DAILY_SECONDS, previous.seconds() + elapsed);
            return new Usage(today, seconds, active && seconds < DAILY_SECONDS ? now : null);
        });
        return new Remaining(false, DAILY_SECONDS - current.seconds());
    }

    public void requireAvailable(Long userId, String ip) {
        if (heartbeat(userId, ip, true).remainingSeconds() == 0 && !fullAccess(userId))
            throw new ApiException(ErrorCode.KANJI_MAP_PREMIUM_REQUIRED);
    }
}
