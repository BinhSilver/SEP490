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

    @Test
    void dialoguePracticeBucketLetsALearnerFlipEveryCardAndPractiseEveryLineQuicklyWithoutTouchingHeartbeats() {
        // §2.24: up to 8 "Từ & cụm từ" cards + 12 lines, each one click; 2026-10-19 Selenium hit the 8/30s heartbeat cap.
        var service = new InteractiveRateLimitService(new MutableClock(0));
        for (int i = 0; i < 20; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.DIALOGUE_PRACTICE);
        }
        for (int i = 0; i < 8; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.DIALOGUE_HEARTBEAT);
        }
        for (int i = 20; i < 60; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.DIALOGUE_PRACTICE);
        }
        assertThatThrownBy(() -> service.check(1L, InteractiveRateLimitService.Bucket.DIALOGUE_PRACTICE))
                .isInstanceOf(ApiException.class);
    }

    // [JLAS-W0193] RC Task 8 hardening — the four tests above already prove the limiter using an injectable
    // Clock, but only ever advance well past the window edge (e.g. +30_001ms/+60_001ms). The eviction
    // predicate itself (`now - requests.peekFirst() >= bucket.windowMillis`) is an off-by-one-prone boundary
    // that was never exercised exactly AT the edge — these four close that gap.

    @Test
    void requestExactlyAtTheWindowBoundaryIsStillRejected() {
        // One millisecond before the oldest request ages out, the bucket is still full: the boundary is
        // exclusive from the caller's point of view (age must reach the full window, not just approach it).
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        for (int i = 0; i < 20; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        }
        clock.advance(59_999L);
        assertThatThrownBy(() -> service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void requestAtExactlyTheFullWindowAgeIsAccepted() {
        // At exactly windowMillis of age (not windowMillis+1), the oldest request is evicted: the limiter's
        // own predicate uses `>=`, so the boundary value itself already frees capacity.
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        for (int i = 0; i < 20; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
        }
        clock.advance(60_000L);
        service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
    }

    @Test
    void slidingWindowFreesCapacityIncrementallyNotAllOrNothing() {
        // A real sliding window, not a fixed-window reset: requests spaced 10s apart age out one at a time
        // as the clock advances, each freeing exactly one slot rather than the whole bucket resetting at once.
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        for (int i = 0; i < 20; i++) {
            service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
            clock.advance(1_000L);
        }
        // Clock is now at 20_000ms; the first request (t=0) is 20_000ms old, nowhere near the 60s window.
        assertThatThrownBy(() -> service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE))
                .isInstanceOf(ApiException.class);
        // Advance so only the very first request (t=0) has aged past 60s; the other 19 (t=1000..19000) have not.
        clock.advance(40_001L); // now = 60_001ms; age of t=0 request = 60_001ms >= 60_000ms window
        service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE); // exactly one slot freed
        assertThatThrownBy(() -> service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void concurrentCheckCallsForTheSameUserAndBucketNeverExceedTheLimit() throws Exception {
        // The limiter is documented as an in-memory, per-instance, best-effort limiter — this proves the
        // in-process synchronization is real (not merely present in the source) under genuine thread overlap;
        // it does NOT prove or simulate cross-instance behavior, which remains the disclosed limitation.
        var clock = new MutableClock(0);
        var service = new InteractiveRateLimitService(clock);
        int threads = 40;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var accepted = new java.util.concurrent.atomic.AtomicInteger(0);
        var rejected = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    go.await();
                    service.check(1L, InteractiveRateLimitService.Bucket.MICRO_CHECK_ISSUE);
                    accepted.incrementAndGet();
                } catch (ApiException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException ignored) {
                    // not expected
                }
            }));
        }
        go.countDown();
        for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(accepted.get()).isEqualTo(20);
        assertThat(rejected.get()).isEqualTo(threads - 20);
    }
}
