package com.jlas.game.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ lập lịch riêng của Game Arena (đếm ngược, hết lượt, nhịp SSE). Tách khỏi scheduler
 * chung của app để job nặng của module khác không làm trễ đồng hồ trận đấu.
 */
@Slf4j
@Component
public class GameScheduler implements DisposableBean {

    private final ScheduledThreadPoolExecutor executor;

    public GameScheduler() {
        AtomicInteger counter = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "game-arena-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
    }

    public ScheduledFuture<?> at(Instant when, Runnable task) {
        long delay = Math.max(0, Duration.between(Instant.now(), when).toMillis());
        return executor.schedule(safe(task), delay, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> after(long delayMs, Runnable task) {
        return executor.schedule(safe(task), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> every(long periodMs, Runnable task) {
        return executor.scheduleWithFixedDelay(safe(task), periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** Task lặp lại sẽ bị huỷ âm thầm nếu ném lỗi, nên mọi task đều được bọc log. */
    private static Runnable safe(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                log.warn("Game arena task failed: {}", ex.getMessage(), ex);
            }
        };
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
