package com.jlas.game.service.impl;

import com.jlas.game.dto.GameDtos.ArenaEvent;
import com.jlas.game.dto.GameDtos.PresencePayload;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.dto.GameDtos.SessionFinishedPayload;
import com.jlas.game.runtime.ArenaEventHub;
import com.jlas.game.runtime.ArenaEventType;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.GameSessionFinishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** Phần dùng chung của mọi engine: phát sự kiện tới người chơi, lưu kết quả, báo phòng khi trận xong. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameSessionLifecycle {

    private final ArenaEventHub hub;
    private final GameMatchRecorder recorder;
    private final ApplicationEventPublisher publisher;

    public void broadcast(GameSession session, String type, Object payload) {
        hub.send(session.userIds(), ArenaEvent.of(type, session.getRoomId(), session.getSessionId(), payload));
    }

    public void presence(GameSession session, long userId, boolean online) {
        broadcast(session, ArenaEventType.SESSION_PRESENCE, new PresencePayload(userId, online));
    }

    /** Gọi đúng một lần, ngoài lock của session. */
    public void completed(GameSession session, ResultSummary summary, Object detail, Set<Long> forfeited) {
        Set<Long> disconnected = new HashSet<>();
        for (Long userId : session.userIds()) {
            if (!session.isOnline(userId)) {
                disconnected.add(userId);
            }
        }
        try {
            recorder.finish(session.getMatchId(), summary, false, forfeited, disconnected);
        } catch (RuntimeException ex) {
            log.warn("Không lưu được kết quả trận {}: {}", session.getSessionId(), ex.getMessage());
        }
        broadcast(session, ArenaEventType.SESSION_FINISHED, new SessionFinishedPayload(summary, detail));
        publisher.publishEvent(new GameSessionFinishedEvent(session.getSessionId(), session.getRoomId(), summary));
    }

    public void aborted(GameSession session) {
        try {
            recorder.finish(session.getMatchId(), null, true, Set.of(), Set.of());
        } catch (RuntimeException ex) {
            log.warn("Không cập nhật được trận bị huỷ {}: {}", session.getSessionId(), ex.getMessage());
        }
        broadcast(session, ArenaEventType.SESSION_FINISHED, new SessionFinishedPayload(null, null));
        publisher.publishEvent(new GameSessionFinishedEvent(session.getSessionId(), session.getRoomId(), null));
    }

    public GameMatchRecorder recorder() {
        return recorder;
    }
}
