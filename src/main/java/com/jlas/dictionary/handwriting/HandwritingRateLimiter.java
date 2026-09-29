package com.jlas.dictionary.handwriting;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn số lần nhận diện mỗi phút theo user (cùng cách làm với PronunciationRateLimitService).
 * FE đã debounce nên người dùng thật chỉ gửi ~1-2 request/giây; giới hạn này chặn client độc hại
 * hoặc script gọi liên tục để đốt CPU. Mặc định 180/phút, chỉnh bằng
 * {@code dictionary.handwriting.requests-per-minute} (<= 0 nghĩa là tắt).
 */
@Component
public class HandwritingRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<Long, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final Clock clock;

    @Autowired
    public HandwritingRateLimiter(@Value("${dictionary.handwriting.requests-per-minute:180}") int limit) {
        this(limit, Clock.systemUTC());
    }

    HandwritingRateLimiter(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    public void check(Long userId) {
        if (limit <= 0 || userId == null) return;
        long now = clock.millis();
        Deque<Long> requests = windows.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (requests) {
            while (!requests.isEmpty() && now - requests.peekFirst() >= WINDOW_MILLIS) {
                requests.removeFirst();
            }
            if (requests.size() >= limit) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                        "Bạn vẽ quá nhanh. Vui lòng đợi một chút rồi thử lại.");
            }
            requests.addLast(now);
        }
    }
}
