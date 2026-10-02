package com.jlas.moderation.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limit in-memory (MVP) cho moderation-sensitive actions.
 * Key: userId hoặc clientId string.
 */
@Service
public class ModerationRateLimitService {

    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    @Value("${moderation.rate-limit.comment-per-minute:30}")
    private int commentPerMinute;

    @Value("${moderation.rate-limit.image-per-hour:40}")
    private int imagePerHour;

    @Value("${moderation.rate-limit.publish-per-hour:20}")
    private int publishPerHour;

    public void checkComment(String key) {
        check(key + ":cmt", commentPerMinute, 60_000L, "Bình luận quá nhanh — thử lại sau.");
    }

    public void checkImageUpload(String key) {
        check(key + ":img", imagePerHour, 3_600_000L, "Upload ảnh quá nhiều — thử lại sau.");
    }

    public void checkPublish(String key) {
        check(key + ":pub", publishPerHour, 3_600_000L, "Publish quá nhiều — thử lại sau.");
    }

    private void check(String bucketKey, int max, long windowMs, String message) {
        if (max <= 0) return;
        long now = System.currentTimeMillis();
        Deque<Long> q = buckets.computeIfAbsent(bucketKey, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
                q.pollFirst();
            }
            if (q.size() >= max) {
                throw new ApiException(ErrorCode.BAD_REQUEST, message);
            }
            q.addLast(now);
        }
    }
}
