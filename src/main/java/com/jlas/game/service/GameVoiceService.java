package com.jlas.game.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.config.GameVoiceProperties;
import com.jlas.game.runtime.ArenaRateLimiter;
import com.jlas.game.runtime.GameRoom;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.GameSessionRegistry;
import com.jlas.game.runtime.RoomRegistry;
import com.jlas.game.util.GameRules;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import livekit.LivekitModels;
import lombok.extern.slf4j.Slf4j;
import com.jlas.moderation.service.UserBanActivatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import retrofit2.Response;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Voice transport and permissions are separate from Arena scoring and SSE presence. */
@Slf4j
@Service
public class GameVoiceService {
    private static final String ROOM_PREFIX = "arena:";
    private static final String USER_PREFIX = "jlas-user:";

    public record VoiceStatus(boolean enabled) { }
    public record VoiceCredentials(String serverUrl, String participantToken) { }
    private record Removal(String roomId, long userId) { }

    private final GameVoiceProperties properties;
    private final RoomRegistry rooms;
    private final GameSessionRegistry sessions;
    private final ArenaRateLimiter rateLimiter;
    private final RoomServiceClient liveKit;
    private final Set<String> createdRooms = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<Long>> issuedParticipants = new ConcurrentHashMap<>();
    private final Set<String> pendingDeletes = ConcurrentHashMap.newKeySet();
    private final Set<Removal> pendingRemovals = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Removal, Long> revokedBefore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> roomLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "arena-voice-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public GameVoiceService(GameVoiceProperties properties, RoomRegistry rooms,
                            GameSessionRegistry sessions, ArenaRateLimiter rateLimiter) {
        this(properties, rooms, sessions, rateLimiter, createClient(properties));
    }

    GameVoiceService(GameVoiceProperties properties, RoomRegistry rooms,
                     GameSessionRegistry sessions, ArenaRateLimiter rateLimiter, RoomServiceClient liveKit) {
        this.properties = properties;
        this.rooms = rooms;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
        this.liveKit = liveKit;
    }

    private static RoomServiceClient createClient(GameVoiceProperties properties) {
        if (properties.enabled()) {
            boolean loopback = properties.allowInsecureLocalhost()
                    && properties.url().matches("ws://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]{1,5})?/?")
                    && Arrays.stream(properties.allowedOrigins().split(",", -1)).map(String::trim)
                    .allMatch(origin -> origin.matches("https?://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]{1,5})?"));
            if (properties.url().isBlank() || properties.apiKey().isBlank() || properties.apiSecret().isBlank()
                    || properties.allowedOrigins().isBlank() || properties.tokenTtlSeconds() < 30
                    || properties.tokenTtlSeconds() > 600 || properties.tokensPerMinute() < 1
                    || (!loopback && !properties.url().startsWith("wss://"))) {
                throw new IllegalStateException("game.voice requires a wss URL (or explicitly enabled loopback transport), credentials, allowed origins and valid limits");
            }
            String apiUrl = loopback ? "http://" + properties.url().substring("ws://".length())
                    : "https://" + properties.url().substring("wss://".length());
            return RoomServiceClient.createClient(apiUrl, properties.apiKey(), properties.apiSecret());
        }
        return null;
    }

    @PostConstruct
    void startCleanup() {
        if (liveKit != null) {
            cleanup.scheduleWithFixedDelay(this::safeReconcile, 30, 30, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    void stopCleanup() {
        cleanup.shutdownNow();
    }

    public VoiceStatus status() {
        return new VoiceStatus(properties.enabled());
    }

    public VoiceCredentials join(long userId, String roomId, String sessionId, String origin) {
        if (liveKit == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Voice chưa được bật");
        }
        if (origin == null || Arrays.stream(properties.allowedOrigins().split(","))
                .map(String::trim).noneMatch(origin::equals)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Nguồn yêu cầu voice không hợp lệ");
        }
        rateLimiter.check(userId, "voice-token", properties.tokensPerMinute());
        GameRoom room = requireMember(userId, roomId, sessionId);
        ensureMediaRoom(roomId);
        // The member may have left while the external room was being created.
        requireMember(userId, roomId, sessionId);
        synchronized (roomLocks.computeIfAbsent(roomId, ignored -> new Object())) {
            return room.withLock(() -> {
                if (room.isClosed() || !room.playersByUserId().containsKey(userId)) {
                    throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không ở trong phòng này");
                }
                if (pendingRemovals.contains(new Removal(roomId, userId)) || pendingDeletes.contains(roomId)) {
                    throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Voice đang cập nhật quyền, vui lòng thử lại");
                }
                long cutoff = revokedBefore.getOrDefault(new Removal(roomId, userId), 0L);
                long waitMillis = cutoff * 1000 - System.currentTimeMillis();
                if (waitMillis > 0) {
                    try {
                        Thread.sleep(waitMillis);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Voice đang cập nhật quyền, vui lòng thử lại");
                    }
                }
                AccessToken token = new AccessToken(properties.apiKey(), properties.apiSecret());
                token.setIdentity(identity(userId));
                token.setTtl(Duration.ofSeconds(properties.tokenTtlSeconds()).toMillis());
                token.addGrants(new RoomJoin(true), new RoomName(mediaRoom(roomId)), new CanPublish(true),
                        new CanPublishSources(List.of("microphone")), new CanSubscribe(true), new CanPublishData(false));
                issuedParticipants.computeIfAbsent(roomId, ignored -> ConcurrentHashMap.newKeySet()).add(userId);
                return new VoiceCredentials(properties.url(), token.toJwt());
            });
        }
    }

    private GameRoom requireMember(long userId, String roomId, String sessionId) {
        GameRoom room = rooms.require(roomId);
        GameRules.requireAccess(room.getGameType());
        boolean allowed = room.withLock(() -> !room.isClosed() && room.playersByUserId().containsKey(userId));
        if (!allowed) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không ở trong phòng này");
        }
        if (sessionId != null && !sessionId.isBlank()) {
            GameSession session = sessions.require(sessionId);
            if (!roomId.equals(session.getRoomId()) || !session.isParticipant(userId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Trận này không thuộc phòng của bạn");
            }
        }
        return room;
    }

    private void ensureMediaRoom(String roomId) {
        Object lock = roomLocks.computeIfAbsent(roomId, ignored -> new Object());
        synchronized (lock) {
            try {
                GameRoom arenaRoom = rooms.require(roomId);
                if (arenaRoom.withLock(arenaRoom::isClosed)) {
                    throw new ApiException(ErrorCode.FORBIDDEN, "Phòng này đã đóng");
                }
                Response<List<LivekitModels.Room>> existing = liveKit.listRooms(List.of(mediaRoom(roomId))).execute();
                if (!existing.isSuccessful() || existing.body() == null) {
                    throw unavailable("list rooms", existing.code());
                }
                LivekitModels.Room current = existing.body().stream()
                        .filter(media -> mediaRoom(roomId).equals(media.getName())).findFirst().orElse(null);
                if (current == null) {
                    Response<LivekitModels.Room> created = liveKit.createRoom(mediaRoom(roomId), 60, 2).execute();
                    if (!created.isSuccessful()) throw unavailable("create room", created.code());
                } else if (current.getMaxParticipants() != 2) {
                    throw unavailable("invalid room limit", 409);
                }
                createdRooms.add(roomId);
            } catch (IOException ex) {
                log.warn("LiveKit create room failed for {}: {}", roomId, ex.toString());
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Không kết nối được dịch vụ voice");
            }
        }
    }

    public void memberLeft(String roomId, long userId) {
        if (liveKit == null || !hasMediaRoom(roomId)) return;
        pendingRemovals.add(new Removal(roomId, userId));
        cleanup.execute(this::safeReconcile);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBanActivated(UserBanActivatedEvent event) {
        rooms.roomOf(event.userId()).ifPresent(room -> memberLeft(room.getRoomId(), event.userId()));
    }

    public void roomClosed(String roomId) {
        if (liveKit == null || !hasMediaRoom(roomId)) return;
        issuedParticipants.getOrDefault(roomId, Set.of()).forEach(userId ->
                pendingRemovals.add(new Removal(roomId, userId)));
        pendingDeletes.add(roomId);
        cleanup.execute(this::safeReconcile);
    }

    private boolean hasMediaRoom(String roomId) {
        return createdRooms.contains(roomId) || roomLocks.containsKey(roomId);
    }

    private void safeReconcile() {
        try {
            reconcile();
        } catch (RuntimeException ex) {
            log.warn("LiveKit cleanup failed: {}", ex.toString());
        }
    }

    private void reconcile() {
        for (Removal removal : pendingRemovals) {
            synchronized (roomLocks.computeIfAbsent(removal.roomId(), ignored -> new Object())) {
                try {
                    long cutoff = Instant.now().getEpochSecond() + 1;
                    Response<Void> response = liveKit.removeParticipant(
                            mediaRoom(removal.roomId()), identity(removal.userId()), cutoff).execute();
                    if (response.isSuccessful() || response.code() == 404) {
                        revokedBefore.put(removal, cutoff);
                        pendingRemovals.remove(removal);
                    } else {
                        log.warn("LiveKit remove participant from {} returned HTTP {}", removal.roomId(), response.code());
                    }
                } catch (IOException ex) {
                    log.warn("LiveKit remove participant from {} failed: {}", removal.roomId(), ex.toString());
                }
            }
        }
        for (String roomId : pendingDeletes) {
            if (pendingRemovals.stream().anyMatch(removal -> removal.roomId().equals(roomId))) continue;
            Object lock = roomLocks.computeIfAbsent(roomId, ignored -> new Object());
            synchronized (lock) {
                try {
                    Response<Void> response = liveKit.deleteRoom(mediaRoom(roomId)).execute();
                    if (response.isSuccessful() || response.code() == 404) {
                        pendingDeletes.remove(roomId);
                        createdRooms.remove(roomId);
                        issuedParticipants.remove(roomId);
                        revokedBefore.keySet().removeIf(removal -> removal.roomId().equals(roomId));
                        roomLocks.remove(roomId, lock);
                    } else {
                        log.warn("LiveKit delete room {} returned HTTP {}", roomId, response.code());
                    }
                } catch (IOException ex) {
                    log.warn("LiveKit delete room {} failed: {}", roomId, ex.toString());
                }
            }
        }
    }

    private static ApiException unavailable(String operation, int status) {
        log.warn("LiveKit {} returned HTTP {}", operation, status);
        return new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Dịch vụ voice tạm thời không khả dụng");
    }

    private static String mediaRoom(String roomId) { return ROOM_PREFIX + roomId; }
    private static String identity(long userId) { return USER_PREFIX + userId; }
}
