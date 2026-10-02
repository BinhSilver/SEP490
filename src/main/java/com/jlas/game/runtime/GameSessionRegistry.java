package com.jlas.game.runtime;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.config.GameProperties;
import com.jlas.game.enums.GameType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Kho các trận đang chạy (và trận vừa xong, giữ lại một lúc để xem kết quả). */
@Component
@RequiredArgsConstructor
public class GameSessionRegistry {

    private final GameProperties properties;
    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();

    public void register(GameSession session) {
        if (sessions.size() >= properties.memory().maxSessions()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Đang có quá nhiều trận, bạn thử lại sau ít phút nhé");
        }
        sessions.put(session.getSessionId(), session);
    }

    public Optional<GameSession> find(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(sessions.get(sessionId));
    }

    public GameSession require(String sessionId) {
        return find(sessionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Trận này đã kết thúc hoặc không còn nữa"));
    }

    /** Trận của đúng loại game và người gọi phải là người chơi của trận. */
    public <T extends GameSession> T require(String sessionId, Class<T> type, long userId) {
        GameSession session = require(sessionId);
        if (!type.isInstance(session)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Trận này thuộc một game khác");
        }
        if (!session.isParticipant(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không tham gia trận này");
        }
        return type.cast(session);
    }

    public <T extends GameSession> List<T> all(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (GameSession session : sessions.values()) {
            if (type.isInstance(session)) {
                result.add(type.cast(session));
            }
        }
        return result;
    }

    public List<GameSession> all() {
        return new ArrayList<>(sessions.values());
    }

    public List<GameSession> of(GameType type) {
        return sessions.values().stream().filter(session -> session.getGameType() == type).toList();
    }

    public void remove(GameSession session) {
        sessions.remove(session.getSessionId());
    }
}
