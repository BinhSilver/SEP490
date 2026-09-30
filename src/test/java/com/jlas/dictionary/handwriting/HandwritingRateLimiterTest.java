package com.jlas.dictionary.handwriting;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandwritingRateLimiterTest {

    /** Đồng hồ chỉnh tay được để test cửa sổ 60 giây mà không phải chờ. */
    private static final class MutableClock extends Clock {
        long millis = 1_000_000L;

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    @Test
    void allowsUpToTheLimitThenRejectsWith429() {
        HandwritingRateLimiter limiter = new HandwritingRateLimiter(3, new MutableClock());

        for (int i = 0; i < 3; i++) limiter.check(1L);
        ApiException error = assertThrows(ApiException.class, () -> limiter.check(1L));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS, error.getErrorCode());
    }

    @Test
    void theWindowSlidesSoAUserRecoversAfterAMinute() {
        MutableClock clock = new MutableClock();
        HandwritingRateLimiter limiter = new HandwritingRateLimiter(2, clock);
        limiter.check(1L);
        limiter.check(1L);
        assertThrows(ApiException.class, () -> limiter.check(1L));

        clock.millis += 59_999;
        assertThrows(ApiException.class, () -> limiter.check(1L));
        clock.millis += 1;
        assertDoesNotThrow(() -> limiter.check(1L));
    }

    @Test
    void usersAreCountedIndependently() {
        HandwritingRateLimiter limiter = new HandwritingRateLimiter(1, new MutableClock());

        limiter.check(1L);

        assertDoesNotThrow(() -> limiter.check(2L));
        assertThrows(ApiException.class, () -> limiter.check(1L));
    }

    @Test
    void nonPositiveLimitDisablesThrottlingAndMissingUserIsIgnored() {
        HandwritingRateLimiter off = new HandwritingRateLimiter(0, new MutableClock());
        for (int i = 0; i < 1000; i++) off.check(1L);

        HandwritingRateLimiter on = new HandwritingRateLimiter(1, new MutableClock());
        for (int i = 0; i < 5; i++) on.check(null);
    }
}
