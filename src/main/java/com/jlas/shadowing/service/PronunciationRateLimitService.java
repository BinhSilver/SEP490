package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.shadowing.config.AzureSpeechPronunciationConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PronunciationRateLimitService {
    private static final long WINDOW_MILLIS = 60_000L;

    private final Map<Long, Deque<Long>> requestWindows = new ConcurrentHashMap<>();
    private final AzureSpeechPronunciationConfig config;
    private final Clock clock;

    @Autowired
    public PronunciationRateLimitService(AzureSpeechPronunciationConfig config) {
        this(config, Clock.systemUTC());
    }

    PronunciationRateLimitService(AzureSpeechPronunciationConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public void check(Long userId) {
        int limit = config.getRequestsPerMinute();
        if (limit <= 0) return;
        long now = clock.millis();
        Deque<Long> requests = requestWindows.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (requests) {
            while (!requests.isEmpty() && now - requests.peekFirst() >= WINDOW_MILLIS) {
                requests.removeFirst();
            }
            if (requests.size() >= limit) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                        "Bạn chấm phát âm quá nhanh. Vui lòng đợi một phút rồi thử lại.");
            }
            requests.addLast(now);
        }
    }
}
