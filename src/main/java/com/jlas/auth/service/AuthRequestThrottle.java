package com.jlas.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Single-instance API limits; persistent OTP attempt limits are enforced separately in MySQL. */
@Service
public class AuthRequestThrottle {
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000).expireAfterAccess(Duration.ofHours(2)).build();

    public void assertLoginAllowed(String email, String clientIp) {
        consume("login:ip:" + ip(clientIp), () -> bucket(60, Duration.ofMinutes(1)));
        consume("login:account:" + email(email), () -> bucket(5, Duration.ofMinutes(5)));
    }

    public void loginSucceeded(String email) {
        // A successful account login must not reset the shared IP allowance.
        buckets.invalidate("login:account:" + email(email));
    }

    public void assertPasswordResetSendAllowed(String email, String clientIp) {
        consume("reset-send:ip:" + ip(clientIp), () -> Bucket.builder()
                .addLimit(b -> b.capacity(10).refillIntervally(10, Duration.ofMinutes(1)))
                .addLimit(b -> b.capacity(30).refillIntervally(30, Duration.ofHours(1))).build());
        consume("reset-send:account:" + email(email), () -> Bucket.builder()
                .addLimit(b -> b.capacity(1).refillIntervally(1, Duration.ofMinutes(1)))
                .addLimit(b -> b.capacity(3).refillIntervally(3, Duration.ofHours(1))).build());
    }

    public void assertPasswordResetCheckAllowed(String email, String clientIp) {
        consume("reset-check:ip:" + ip(clientIp), () -> bucket(30, Duration.ofMinutes(1)));
        consume("reset-check:account:" + email(email), () -> bucket(20, Duration.ofMinutes(15)));
    }

    private void consume(String key, Supplier<Bucket> create) {
        ConsumptionProbe probe = buckets.get(key, ignored -> create.get()).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                    "Bạn đã thử quá nhiều lần. Vui lòng thử lại sau " + seconds + " giây.");
        }
    }

    private static Bucket bucket(long capacity, Duration period) {
        return Bucket.builder().addLimit(b -> b.capacity(capacity).refillIntervally(capacity, period)).build();
    }

    private static String email(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String ip(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
