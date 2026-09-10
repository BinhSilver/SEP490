package com.jlas.auth.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Server-side protection against repeated password guessing. */
@Service
public class LoginAttemptThrottle {
    private static final int MAX_FAILURES_BEFORE_WAIT = 5;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final long INITIAL_WAIT_SECONDS = 30;
    private static final long MAX_WAIT_SECONDS = Duration.ofMinutes(15).toSeconds();

    private final ConcurrentMap<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public void assertAllowed(String email, String clientIp) {
        String key = key(email, clientIp);
        AttemptState state = attempts.get(key);
        if (state == null) return;

        synchronized (state) {
            LocalDateTime now = LocalDateTime.now();
            state.resetIfWindowExpired(now);
            if (state.failures == 0) {
                attempts.remove(key, state);
                return;
            }
            if (state.blockedUntil != null && state.blockedUntil.isAfter(now)) {
                long remaining = Math.max(1, Duration.between(now, state.blockedUntil).toSeconds() + 1);
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                        "Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau " + remaining + " giây.");
            }
            state.blockedUntil = null;
        }
    }

    public void recordFailure(String email, String clientIp) {
        AttemptState state = attempts.computeIfAbsent(key(email, clientIp), ignored -> new AttemptState());
        synchronized (state) {
            LocalDateTime now = LocalDateTime.now();
            state.resetIfWindowExpired(now);
            if (state.windowStartedAt == null) state.windowStartedAt = now;
            state.failures++;
            if (state.failures >= MAX_FAILURES_BEFORE_WAIT) {
                int escalation = Math.min(state.failures - MAX_FAILURES_BEFORE_WAIT, 5);
                long waitSeconds = Math.min(MAX_WAIT_SECONDS, INITIAL_WAIT_SECONDS * (1L << escalation));
                state.blockedUntil = now.plusSeconds(waitSeconds);
            }
        }
    }

    public void recordSuccess(String email, String clientIp) {
        attempts.remove(key(email, clientIp));
    }

    private static String key(String email, String clientIp) {
        return email + '\u0000' + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
    }

    private static final class AttemptState {
        private int failures;
        private LocalDateTime windowStartedAt;
        private LocalDateTime blockedUntil;

        private void resetIfWindowExpired(LocalDateTime now) {
            if (windowStartedAt != null && !windowStartedAt.plus(ATTEMPT_WINDOW).isAfter(now)) {
                failures = 0;
                windowStartedAt = null;
                blockedUntil = null;
            }
        }
    }
}
