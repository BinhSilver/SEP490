package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [JLAS-W0193] Task 10 — per-user sliding-window rate limiter for the V2 issue/submit/heartbeat-shaped
 * endpoints (design spec §11 "Rate limit heartbeat và quiz issue để tránh spam"). Reuses the exact pattern
 * already shipped for this codebase's other best-effort, in-memory limiters
 * ({@code shadowing.service.PronunciationRateLimitService}, {@code moderation.service.ModerationRateLimitService})
 * rather than inventing a new mechanism or pulling in a library — a per-user {@link ArrayDeque} of request
 * timestamps per named bucket, pruned lazily on each check. Not distributed (a multi-instance deployment would
 * need a shared store), which is an accepted, disclosed limitation matching the two existing limiters this one
 * copies.
 */
@Service
public class InteractiveRateLimitService {

    /** One named limit per endpoint family. Values are deliberately generous for genuine use (a learner never
     * legitimately issues/submits a micro-check or a certificate page view more than a handful of times a
     * minute) while still rejecting scripted spam. Dialogue's heartbeat cadence is server-mandated at ~15s
     * (design spec §3.5), so its window/limit is tuned tighter around that cadence rather than reusing the
     * generic per-minute shape. */
    public enum Bucket {
        MICRO_CHECK_ISSUE(20, 60_000L, "Bạn đang mở bài kiểm tra quá nhanh. Vui lòng đợi một chút rồi thử lại."),
        MICRO_CHECK_SUBMIT(20, 60_000L, "Bạn đang nộp bài quá nhanh. Vui lòng đợi một chút rồi thử lại."),
        // [JLAS-W0113] Autosave is a legitimate high-frequency write (debounced client-side), so its ceiling is
        // deliberately looser than issue/submit while still rejecting a scripted write loop.
        MICRO_CHECK_DRAFT(120, 60_000L, "Đang lưu nháp quá nhanh. Vui lòng thử lại sau."),
        QUIZ_EXPLANATION(10, 60_000L, "Bạn đang yêu cầu giải thích quá nhanh. Vui lòng đợi một chút rồi thử lại."),
        DIALOGUE_HEARTBEAT(8, 30_000L, "Hội thoại đang gửi tín hiệu quá nhanh. Vui lòng thử lại sau."),
        // Communication practice clicks (flip a "Từ & cụm từ" card, mark a line practised) are idempotent and one
        // dialogue needs up to ~20 of them in quick succession, so they get their own looser bucket.
        DIALOGUE_PRACTICE(60, 30_000L, "Bạn đang thao tác quá nhanh. Vui lòng thử lại sau giây lát."),
        CERTIFICATE_VIEW(20, 60_000L, "Bạn đang mở chứng nhận quá nhanh. Vui lòng đợi một chút rồi thử lại."),
        // Owner 2026-10-21: optional AI practice sentences; each request may call the AI provider.
        GRAMMAR_EXTRA_PRACTICE(10, 60_000L, "Bạn đang tạo câu luyện thêm quá nhanh. Vui lòng đợi một chút rồi thử lại.");

        private final int limit;
        private final long windowMillis;
        private final String message;

        Bucket(int limit, long windowMillis, String message) {
            this.limit = limit;
            this.windowMillis = windowMillis;
            this.message = message;
        }
    }

    private final Map<String, Deque<Long>> requestWindows = new ConcurrentHashMap<>();
    private final Clock clock;

    public InteractiveRateLimitService() {
        this(Clock.systemUTC());
    }

    /** Package-visible-plus (public, not {@code @Autowired}) so tests can inject a deterministic clock
     * without a real sleep; Spring always uses the no-arg constructor above. */
    public InteractiveRateLimitService(Clock clock) {
        this.clock = clock;
    }

    /** Throws {@link ErrorCode#TOO_MANY_REQUESTS} once {@code userId} has exceeded {@code bucket}'s own
     * limit within its own window; otherwise records this call and returns normally. Never trusts anything
     * from the client — the limiter is keyed purely by the server-resolved principal id and bucket name. */
    public void check(Long userId, Bucket bucket) {
        String key = bucket.name() + ":" + userId;
        long now = clock.millis();
        Deque<Long> requests = requestWindows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (requests) {
            while (!requests.isEmpty() && now - requests.peekFirst() >= bucket.windowMillis) {
                requests.removeFirst();
            }
            if (requests.size() >= bucket.limit) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, bucket.message);
            }
            requests.addLast(now);
        }
    }
}
