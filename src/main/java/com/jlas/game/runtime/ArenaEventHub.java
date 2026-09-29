package com.jlas.game.runtime;

import com.jlas.game.dto.GameDtos.ArenaEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kênh realtime server → trình duyệt bằng Server-Sent Events. Mỗi user có thể mở nhiều tab;
 * sự kiện gửi tới mọi kết nối của user. Trình duyệt gửi thao tác lên bằng REST.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArenaEventHub {

    /** Kết nối tự đóng sau chừng này; EventSource ở trình duyệt tự nối lại. */
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    /** Nhịp giữ kết nối, đồng thời phát hiện kết nối đã chết khi ghi lỗi. */
    private static final long HEARTBEAT_MS = 15_000;

    private final ApplicationEventPublisher publisher;
    private final GameScheduler scheduler;
    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @PostConstruct
    void startHeartbeat() {
        scheduler.every(HEARTBEAT_MS, this::heartbeat);
    }

    public SseEmitter subscribe(long userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        AtomicBoolean first = new AtomicBoolean();
        emitters.compute(userId, (id, set) -> {
            Set<SseEmitter> target = set != null ? set : ConcurrentHashMap.newKeySet();
            first.set(target.isEmpty());
            target.add(emitter);
            return target;
        });
        emitter.onCompletion(() -> detach(userId, emitter));
        emitter.onTimeout(() -> {
            detach(userId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> detach(userId, emitter));

        deliver(userId, emitter, ArenaEvent.of(ArenaEventType.HELLO, null, null, Map.of("userId", userId)));
        if (first.get()) {
            publisher.publishEvent(new ArenaPresenceEvent(userId, true));
        }
        return emitter;
    }

    public boolean isOnline(long userId) {
        return emitters.containsKey(userId);
    }

    public int onlineCount() {
        return emitters.size();
    }

    public void send(long userId, ArenaEvent event) {
        Set<SseEmitter> targets = emitters.get(userId);
        if (targets == null) {
            return;
        }
        for (SseEmitter emitter : targets) {
            deliver(userId, emitter, event);
        }
    }

    public void send(Collection<Long> userIds, ArenaEvent event) {
        for (Long userId : userIds) {
            send(userId, event);
        }
    }

    private void deliver(long userId, SseEmitter emitter, ArenaEvent event) {
        try {
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            // Trình duyệt đã đóng tab/mất mạng: bỏ kết nối này, EventSource sẽ nối lại nếu còn mở.
            log.debug("SSE {} tới user {} thất bại: {}", event.type(), userId, ex.toString());
            detach(userId, emitter);
            safeComplete(emitter);
        }
    }

    private void heartbeat() {
        emitters.forEach((userId, set) -> {
            for (SseEmitter emitter : set) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception ex) {
                    detach(userId, emitter);
                    safeComplete(emitter);
                }
            }
        });
    }

    private void detach(long userId, SseEmitter emitter) {
        AtomicBoolean last = new AtomicBoolean();
        emitters.computeIfPresent(userId, (id, set) -> {
            if (!set.remove(emitter)) {
                return set;
            }
            if (set.isEmpty()) {
                last.set(true);
                return null;
            }
            return set;
        });
        if (last.get()) {
            publisher.publishEvent(new ArenaPresenceEvent(userId, false));
        }
    }

    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Kết nối đã đóng.
        }
    }
}
