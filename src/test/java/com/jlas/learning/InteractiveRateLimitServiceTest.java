package com.jlas.learning;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.service.impl.InteractiveRateLimitService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [JLAS-W0193] Task 10 — {@link InteractiveRateLimitService} unit tests: per-user, per-bucket sliding
 * window, independent buckets/users, and window expiry using an injectable {@link Clock} (no real sleeps). */
class InteractiveRateLimitServiceTest {

    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long startMillis) {
            this.millis = new AtomicLong(startMillis);
        }

        void advance(long deltaMillis) {
            millis.addAndGet(deltaMillis);
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    @Test
    void allowsRequestsUpToTheBucketLimit() {
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        for (int i = 0; i < 20; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        }
    }

    @Test
    void rejectsTheRequestThatExceedsTheLimitWithinTheWindow() {
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        for (int i = 0; i < 20; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        }
        assertThatThrownBy(() -> service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
    }

    @Test
    void oldRequestsOutsideTheWindowAreEvictedFreeingUpCapacity() {
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        for (int i = 0; i < 20; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        }
        clock.advance(60_001L);
        // Every prior request has aged out of the 60s window, so a fresh call succeeds.
        service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
    }

    @Test
    void bucketsAreIndependentPerUser() {
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        for (int i = 0; i < 20; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        }
        // A different user has their own untouched window.
        service.check(2L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
    }

    @Test
    void bucketsAreIndependentPerEndpointFamily() {
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        for (int i = 0; i < 20; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        }
        // Submit has its own, separately-tracked bucket even for the same user.
        service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_SUBMIT);
    }

    @Test
    void dialogueHeartbeatBucketHasItsOwnTighterLimitAndWindow() {
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        for (int i = 0; i < 8; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.DIALOGUE_HEARTBEAT);
        }
        assertThatThrownBy(() -> service.check(1L, InteractiveRateLimitService.Bucket.DIALOGUE_HEARTBEAT))
                .isInstanceOf(ApiException.class);
        clock.advance(30_001L);
        service.check(1L, InteractiveRateLimitService.Bucket.DIALOGUE_HEARTBEAT);
    }
}
