package com.jlas.dictionary.handwriting;

import com.jlas.common.exception.ApiException;
import java.time.Clock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandwritingRateLimiterTest {

    @Test
    void guestIpHasItsOwnLimit() {
        HandwritingRateLimiter limiter = new HandwritingRateLimiter(1, Clock.systemUTC());

        limiter.check("ip:127.0.0.1");
        assertThrows(ApiException.class, () -> limiter.check("ip:127.0.0.1"));
        assertDoesNotThrow(() -> limiter.check("user:1"));
        assertDoesNotThrow(() -> limiter.check("ip:127.0.0.2"));
    }
}
