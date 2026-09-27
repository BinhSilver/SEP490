package com.jlas.game.runtime;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Đếm thao tác theo cửa sổ 1 phút cho từng người chơi, chặn spam request vào trận. */
@Component
public class ArenaRateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void check(long userId, String action, int perMinute) {
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(userId + ":" + action, (key, old) ->
                old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (window.count() > perMinute) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, "Thao tác quá nhanh, chậm lại một chút nhé");
        }
    }

    /** Xoá cửa sổ của các phút đã qua; gọi từ bộ dọn dẹp định kỳ. */
    public void purge() {
        long minute = System.currentTimeMillis() / 60_000;
        windows.values().removeIf(window -> window.minute() < minute);
    }

    private record Window(long minute, int count) {
    }
}
