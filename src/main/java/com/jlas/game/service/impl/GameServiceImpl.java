package com.jlas.game.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.config.GameProperties;
import com.jlas.game.dto.GameDtos.ArenaEvent;
import com.jlas.game.dto.GameDtos.CatalogView;
import com.jlas.game.dto.GameDtos.CreateRoomRequest;
import com.jlas.game.dto.GameDtos.DeckDetailView;
import com.jlas.game.dto.GameDtos.DeckPairView;
import com.jlas.game.dto.GameDtos.DraftChoiceRequest;
import com.jlas.game.dto.GameDtos.FieldView;
import com.jlas.game.dto.GameDtos.GameOptions;
import com.jlas.game.dto.GameDtos.GameSettings;
import com.jlas.game.dto.GameDtos.LaunchView;
import com.jlas.game.dto.GameDtos.LobbyView;
import com.jlas.game.dto.GameDtos.QuickMatchFoundPayload;
import com.jlas.game.dto.GameDtos.QuickMatchRequest;
import com.jlas.game.dto.GameDtos.QuickMatchView;
import com.jlas.game.dto.GameDtos.RoomClosedPayload;
import com.jlas.game.dto.GameDtos.RoomStartedPayload;
import com.jlas.game.dto.GameDtos.RoomView;
import com.jlas.game.dto.GameDtos.SessionInfoView;
import com.jlas.game.dto.GameDtos.SoloRequest;
import com.jlas.game.enums.DeckField;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.PlayMode;
import com.jlas.game.enums.RoomStatus;
import com.jlas.game.enums.RoomVisibility;
import com.jlas.game.mapper.GameMapper;
import com.jlas.game.runtime.ArenaEventHub;
import com.jlas.game.runtime.ArenaEventType;
import com.jlas.game.runtime.ArenaPresenceEvent;
import com.jlas.game.runtime.ArenaRateLimiter;
import com.jlas.game.runtime.GameRoom;
import com.jlas.game.runtime.GameScheduler;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.GameSessionFinishedEvent;
import com.jlas.game.runtime.GameSessionRegistry;
import com.jlas.game.runtime.LaunchRequest;
import com.jlas.game.runtime.PlayerSeat;
import com.jlas.game.runtime.QuickDraft;
import com.jlas.game.runtime.RoomPlayer;
import com.jlas.game.runtime.RoomRegistry;
import com.jlas.game.service.GameDeckService;
import com.jlas.game.service.GameDeckService.DeckWords;
import com.jlas.game.service.GameEngine;
import com.jlas.game.service.GameService;
import com.jlas.game.util.GameRules;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cài đặt {@link GameService}. Phòng và trận chỉ nằm trong bộ nhớ; DB chỉ lưu trận lúc bắt đầu/kết thúc.
 * Không bao giờ giữ lock của hai phòng cùng lúc: rời phòng cũ xong mới khoá phòng mới.
 */
@Slf4j
@Service
public class GameServiceImpl implements GameService {

    private static final long TICK_MS = 1_000;
    private static final Duration STALE_RUNNING_SESSION = Duration.ofMinutes(30);
    /** Vé ghép trận nhanh tự huỷ nếu chờ quá lâu. */
    private static final Duration QUICK_TICKET_TTL = Duration.ofMinutes(10);

    /** Một người đang chờ ghép trận nhanh. Thứ tự chèn = thứ tự chờ (ai chờ lâu nhất được ghép trước). */
    private record QuickTicket(long userId, GameType gameType, long setId, String setTitle, PlayerSeat seat,
                               Instant enqueuedAt) {
    }

    private final Object queueLock = new Object();
    private final Map<Long, QuickTicket> tickets = new LinkedHashMap<>();

    private final RoomRegistry rooms;
    private final GameSessionRegistry sessions;
    private final GameDeckService decks;
    private final GameMatchRecorder recorder;
    private final GameMapper mapper;
    private final ArenaEventHub hub;
    private final ArenaRateLimiter rateLimiter;
    private final GameScheduler scheduler;
    private final GameProperties properties;
    private final UserRepository users;
    private final Map<GameType, GameEngine> engines = new EnumMap<>(GameType.class);

    public GameServiceImpl(RoomRegistry rooms, GameSessionRegistry sessions, GameDeckService decks,
                           GameMatchRecorder recorder, GameMapper mapper, ArenaEventHub hub,
                           ArenaRateLimiter rateLimiter, GameScheduler scheduler, GameProperties properties,
                           UserRepository users, List<GameEngine> engineBeans) {
        this.rooms = rooms;
        this.sessions = sessions;
        this.decks = decks;
        this.recorder = recorder;
        this.mapper = mapper;
        this.hub = hub;
        this.rateLimiter = rateLimiter;
        this.scheduler = scheduler;
        this.properties = properties;
        this.users = users;
        for (GameEngine engine : engineBeans) {
            engines.put(engine.type(), engine);
        }
    }

    @PostConstruct
    void startTimers() {
        scheduler.every(TICK_MS, this::tick);
        scheduler.every(properties.arena().sweepIntervalMs(), this::sweep);
    }

    // ----------------------------------------------------------------- catalog

    @Override
    public CatalogView catalog() {
        List<FieldView> fields = Arrays.stream(DeckField.values())
                .map(field -> new FieldView(field, field.label(), field.japanese()))
                .toList();
        return new CatalogView(GameRules.catalog(), fields);
    }

    @Override
    public LobbyView lobby(long userId) {
        int activeMatches = (int) sessions.all().stream()
                .filter(session -> session.isVersus() && !session.getStatus().isTerminal())
                .count();
        String myRoomId = rooms.roomOf(userId).map(GameRoom::getRoomId).orElse(null);
        Map<GameType, Integer> searching = new EnumMap<>(GameType.class);
        for (GameType type : GameType.values()) {
            searching.put(type, 0);
        }
        synchronized (queueLock) {
            for (QuickTicket ticket : tickets.values()) {
                if (ticket.userId() != userId) {
                    searching.merge(ticket.gameType(), 1, Integer::sum);
                }
            }
        }
        return new LobbyView(hub.onlineCount(), activeMatches, myRoomId, searching, quickMatchStatus(userId));
    }

    // ------------------------------------------------------------------- rooms

    @Override
    public RoomView createRoom(long userId, CreateRoomRequest request) {
        rateLimiter.check(userId, "room-join", properties.arena().rateLimit().joinPerMinute());
        GameType type = request.gameType();
        GameSettings settings = validatedSettings(type, PlayMode.VERSUS, request.settings(), userId);
        cancelQuickMatch(userId);
        leaveCurrentRoom(userId, null);

        PlayerSeat me = seatOf(userId, 0);
        Instant now = Instant.now();
        RoomVisibility visibility = request.visibility() == null ? RoomVisibility.PRIVATE : request.visibility();
        GameRoom room = new GameRoom(UUID.randomUUID().toString().replace("-", "").substring(0, 12), rooms.newCode(),
                type, visibility, userId, settings, now, false);
        room.playersByUserId().put(userId, new RoomPlayer(userId, me.displayName(), me.avatarUrl(), 0, now));
        rooms.register(room);
        rooms.bindUser(userId, room.getRoomId());
        log.info("Phòng {} ({}) được tạo bởi user {}", room.getCode(), type, userId);
        return room.withLock(() -> mapper.toRoomView(room));
    }

    @Override
    public RoomView joinByCode(long userId, String code) {
        GameRoom room = rooms.findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phòng với mã này"));
        return join(userId, room.getRoomId());
    }

    @Override
    public RoomView join(long userId, String roomId) {
        rateLimiter.check(userId, "room-join", properties.arena().rateLimit().joinPerMinute());
        GameRoom room = rooms.require(roomId);
        boolean alreadyMember = room.withLock(() -> room.playersByUserId().containsKey(userId));
        if (alreadyMember) {
            return room(userId, roomId);
        }
        if (room.isQuickMatch()) {
            throw new ApiException(ErrorCode.CONFLICT, "Phòng ghép trận nhanh không nhận thêm người");
        }
        cancelQuickMatch(userId);
        leaveCurrentRoom(userId, roomId);
        PlayerSeat me = seatOf(userId, 0);
        RoomView view = room.withLock(() -> {
            if (room.isClosed()) {
                throw new ApiException(ErrorCode.NOT_FOUND, "Phòng đã đóng");
            }
            if (room.playersByUserId().containsKey(userId)) {
                return mapper.toRoomView(room);
            }
            if (room.getStatus() != RoomStatus.WAITING) {
                throw new ApiException(ErrorCode.CONFLICT, "Phòng đang trong trận, chờ trận kết thúc rồi vào lại");
            }
            if (room.isFull()) {
                throw new ApiException(ErrorCode.CONFLICT, "Phòng đã đủ 2 người");
            }
            Instant now = Instant.now();
            room.playersByUserId().put(userId,
                    new RoomPlayer(userId, me.displayName(), me.avatarUrl(), room.freeSlot(), now));
            rooms.bindUser(userId, roomId);
            room.touch(now);
            return mapper.toRoomView(room);
        });
        broadcastRoom(room);
        return view;
    }

    @Override
    public RoomView room(long userId, String roomId) {
        GameRoom room = rooms.require(roomId);
        return room.withLock(() -> {
            if (!room.playersByUserId().containsKey(userId)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Bạn chưa ở trong phòng này");
            }
            return mapper.toRoomView(room);
        });
    }

    @Override
    public RoomView updateSettings(long userId, String roomId, GameSettings raw) {
        rateLimiter.check(userId, "room-settings", properties.arena().rateLimit().settingsPerMinute());
        GameRoom room = rooms.require(roomId);
        requireHost(room, userId);
        if (room.isQuickMatch()) {
            throw new ApiException(ErrorCode.CONFLICT, "Phòng ghép trận nhanh chọn cài đặt bằng tung đồng xu");
        }
        GameSettings settings = validatedSettings(room.getGameType(), PlayMode.VERSUS, raw, userId);
        RoomView view = room.withLock(() -> {
            if (room.getHostUserId() != userId) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Chỉ chủ phòng đổi được cài đặt");
            }
            if (room.isQuickMatch()) {
                throw new ApiException(ErrorCode.CONFLICT, "Phòng ghép trận nhanh chọn cài đặt bằng tung đồng xu");
            }
            if (room.getStatus() != RoomStatus.WAITING) {
                throw new ApiException(ErrorCode.CONFLICT, "Không đổi cài đặt khi trận đã bắt đầu");
            }
            room.setSettings(settings);
            // Cài đặt đổi thì khách phải xác nhận sẵn sàng lại.
            room.playersByUserId().values().forEach(player -> player.setReady(false));
            room.touch(Instant.now());
            return mapper.toRoomView(room);
        });
        broadcastRoom(room);
        return view;
    }

    @Override
    public RoomView setReady(long userId, String roomId, boolean ready) {
        rateLimiter.check(userId, "room-ready", properties.arena().rateLimit().readyPerMinute());
        GameRoom room = rooms.require(roomId);
        RoomView view = room.withLock(() -> {
            RoomPlayer player = requireMember(room, userId);
            if (room.getStatus() != RoomStatus.WAITING) {
                throw new ApiException(ErrorCode.CONFLICT, "Trận đã bắt đầu");
            }
            player.setReady(ready);
            room.touch(Instant.now());
            // Ghép trận nhanh: không có nút "Bắt đầu", cả hai sẵn sàng là tự đếm ngược vào trận.
            boolean everyoneReady = room.playersByUserId().size() == GameRoom.MAX_PLAYERS
                    && room.playersByUserId().values().stream().allMatch(RoomPlayer::isReady);
            if (room.isQuickMatch() && everyoneReady) {
                beginCountdownLocked(room);
            }
            return mapper.toRoomView(room);
        });
        broadcastRoom(room);
        return view;
    }

    @Override
    public RoomView start(long userId, String roomId) {
        rateLimiter.check(userId, "room-start", properties.arena().rateLimit().startPerMinute());
        GameRoom room = rooms.require(roomId);
        requireHost(room, userId);
        GameSettings settings = room.withLock(room::getSettings);
        // Kiểm lại bộ thẻ ngay lúc bắt đầu: chủ bộ có thể đã sửa hoặc ẩn bộ.
        validatedSettings(room.getGameType(), PlayMode.VERSUS, settings, userId);
        RoomView view = room.withLock(() -> {
            if (room.getHostUserId() != userId) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Chỉ chủ phòng bắt đầu được trận");
            }
            if (room.getStatus() != RoomStatus.WAITING) {
                throw new ApiException(ErrorCode.CONFLICT, "Trận đã bắt đầu");
            }
            if (room.playersByUserId().size() < GameRoom.MAX_PLAYERS) {
                throw new ApiException(ErrorCode.CONFLICT, "Cần đủ 2 người để bắt đầu");
            }
            boolean guestsReady = room.playersByUserId().values().stream()
                    .allMatch(player -> player.getUserId() == room.getHostUserId() || player.isReady());
            if (!guestsReady) {
                throw new ApiException(ErrorCode.CONFLICT, "Đối thủ chưa sẵn sàng");
            }
            beginCountdownLocked(room);
            return mapper.toRoomView(room);
        });
        broadcastRoom(room);
        return view;
    }

    /** Gọi trong lock của phòng. */
    private void beginCountdownLocked(GameRoom room) {
        Instant endsAt = Instant.now().plusSeconds(properties.arena().countdownSeconds());
        room.setStatus(RoomStatus.COUNTDOWN);
        room.setCountdownEndsAt(endsAt);
        room.setCountdownTask(scheduler.at(endsAt, () -> launchRoom(room)));
        room.touch(Instant.now());
    }

    /** Hết đếm ngược: nạp bộ thẻ, lưu trận, mở engine rồi đưa cả hai vào trang chơi. */
    private void launchRoom(GameRoom room) {
        record Snapshot(GameSettings settings, List<PlayerSeat> seats, long hostUserId, String code) {
        }
        Snapshot snapshot = room.withLock(() -> {
            if (room.isClosed() || room.getStatus() != RoomStatus.COUNTDOWN) {
                return null;
            }
            List<PlayerSeat> seats = room.orderedPlayers().stream().map(RoomPlayer::toSeat).toList();
            return new Snapshot(room.getSettings(), seats, room.getHostUserId(), room.getCode());
        });
        if (snapshot == null) {
            return;
        }
        GameSession session;
        try {
            session = openSession(room.getGameType(), PlayMode.VERSUS, room.getRoomId(), snapshot.code(),
                    snapshot.settings(), snapshot.seats(), snapshot.hostUserId());
        } catch (RuntimeException ex) {
            log.warn("Không mở được trận cho phòng {}: {}", room.getCode(), ex.getMessage());
            room.withLock(() -> {
                room.setStatus(RoomStatus.WAITING);
                room.setCountdownEndsAt(null);
                room.setCountdownTask(null);
                room.touch(Instant.now());
            });
            notice(room, ex instanceof ApiException ? ex.getMessage() : "Không mở được trận, thử lại nhé");
            broadcastRoom(room);
            return;
        }
        boolean started = room.withLock(() -> {
            // Ai đó rời phòng đúng lúc đếm ngược xong: huỷ trận vừa mở.
            if (room.isClosed() || room.getStatus() != RoomStatus.COUNTDOWN
                    || room.playersByUserId().size() < GameRoom.MAX_PLAYERS) {
                return false;
            }
            room.setStatus(RoomStatus.PLAYING);
            room.setSessionId(session.getSessionId());
            room.setCountdownEndsAt(null);
            room.setCountdownTask(null);
            room.touch(Instant.now());
            return true;
        });
        if (!started) {
            engine(session.getGameType()).abort(session);
            return;
        }
        List<Long> members = room.withLock(room::memberIds);
        hub.send(members, ArenaEvent.of(ArenaEventType.ROOM_STARTED, room.getRoomId(), session.getSessionId(),
                new RoomStartedPayload(session.getSessionId(), session.getGameType(),
                        session.getGameType().playUrl(session.getSessionId()))));
        broadcastRoom(room);
    }

    @Override
    public void leave(long userId, String roomId) {
        GameRoom room = rooms.find(roomId).orElse(null);
        if (room == null) {
            return;
        }
        record LeaveResult(boolean member, boolean closed, String sessionId) {
        }
        LeaveResult result = room.withLock(() -> {
            if (room.playersByUserId().remove(userId) == null) {
                return new LeaveResult(false, false, null);
            }
            rooms.unbindUser(userId, roomId);
            if (room.getDraftTask() != null) {
                room.getDraftTask().cancel(false);
                room.setDraftTask(null);
            }
            if (room.getStatus() == RoomStatus.DRAFTING) {
                room.setStatus(RoomStatus.WAITING);
            }
            if (room.getStatus() == RoomStatus.COUNTDOWN) {
                if (room.getCountdownTask() != null) {
                    room.getCountdownTask().cancel(false);
                }
                room.setStatus(RoomStatus.WAITING);
                room.setCountdownEndsAt(null);
                room.setCountdownTask(null);
            }
            String playingSession = room.getStatus() == RoomStatus.PLAYING ? room.getSessionId() : null;
            if (room.playersByUserId().isEmpty()) {
                room.setClosed(true);
                return new LeaveResult(true, true, playingSession);
            }
            if (room.getHostUserId() == userId) {
                room.setHostUserId(room.orderedPlayers().get(0).getUserId());
            }
            room.playersByUserId().values().forEach(player -> player.setReady(false));
            room.touch(Instant.now());
            return new LeaveResult(true, false, playingSession);
        });
        if (!result.member()) {
            return;
        }
        if (result.sessionId() != null) {
            sessions.find(result.sessionId()).ifPresent(session -> engine(session.getGameType()).forfeit(session, userId));
        }
        if (result.closed()) {
            rooms.remove(room);
            log.info("Phòng {} đóng (không còn ai)", room.getCode());
        } else {
            notice(room, "Đối thủ đã rời phòng");
            broadcastRoom(room);
        }
    }

    // ------------------------------------------------------------- quick match

    @Override
    public QuickMatchView quickMatch(long userId, QuickMatchRequest request) {
        rateLimiter.check(userId, "quick-match", properties.arena().rateLimit().joinPerMinute());
        GameType type = request.gameType();
        DeckDetailView detail = decks.deck(userId, request.setId());
        if (detail.deck().draft()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bộ này đang là bản nháp; xuất bản bộ rồi mới ghép trận được");
        }
        int need = QuickDraft.minCardsForFields(type);
        if (detail.pairs().stream().noneMatch(pair -> pair.usableCards() >= need)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bộ \"" + detail.deck().title() + "\" chưa có cặp hỏi – đáp nào đủ "
                    + need + " thẻ để chơi " + GameRules.gameName(type) + ".");
        }
        leaveCurrentRoom(userId, null);
        Instant now = Instant.now();
        QuickTicket ticket = new QuickTicket(userId, type, detail.deck().id(), detail.deck().title(),
                seatOf(userId, 0), now);
        QuickTicket partner;
        synchronized (queueLock) {
            tickets.remove(userId);
            partner = tickets.values().stream()
                    .filter(other -> other.gameType() == type && other.setId() == ticket.setId()
                            && other.userId() != userId && hub.isOnline(other.userId()))
                    .findFirst()
                    .orElse(null);
            if (partner == null) {
                tickets.put(userId, ticket);
            } else {
                tickets.remove(partner.userId());
            }
        }
        if (partner == null) {
            log.info("User {} chờ ghép {} với bộ {}", userId, type, ticket.setId());
            return ticketView(ticket, now);
        }
        GameRoom room = openQuickRoom(partner, ticket, detail.pairs());
        return new QuickMatchView("MATCHED", type, GameRules.gameName(type), ticket.setId(), ticket.setTitle(), 0,
                searchingCount(type, userId), room.getRoomId());
    }

    @Override
    public QuickMatchView quickMatchStatus(long userId) {
        QuickTicket ticket;
        synchronized (queueLock) {
            ticket = tickets.get(userId);
        }
        if (ticket != null) {
            return ticketView(ticket, Instant.now());
        }
        Optional<GameRoom> room = rooms.roomOf(userId).filter(GameRoom::isQuickMatch);
        if (room.isPresent()) {
            GameRoom quickRoom = room.get();
            return quickRoom.withLock(() -> new QuickMatchView("MATCHED", quickRoom.getGameType(),
                    GameRules.gameName(quickRoom.getGameType()), quickRoom.getSettings().setId(),
                    quickRoom.getSettings().setTitle(), 0, 0, quickRoom.getRoomId()));
        }
        return new QuickMatchView("IDLE", null, null, null, null, 0, 0, null);
    }

    @Override
    public void cancelQuickMatch(long userId) {
        synchronized (queueLock) {
            tickets.remove(userId);
        }
    }

    private QuickMatchView ticketView(QuickTicket ticket, Instant now) {
        return new QuickMatchView("SEARCHING", ticket.gameType(), GameRules.gameName(ticket.gameType()), ticket.setId(),
                ticket.setTitle(), Duration.between(ticket.enqueuedAt(), now).toMillis(),
                searchingCount(ticket.gameType(), ticket.userId()), null);
    }

    private int searchingCount(GameType type, long exceptUserId) {
        synchronized (queueLock) {
            return (int) tickets.values().stream()
                    .filter(ticket -> ticket.gameType() == type && ticket.userId() != exceptUserId)
                    .count();
        }
    }

    /**
     * Hai người cùng game, cùng bộ: mở phòng riêng và bắt đầu tung đồng xu chọn cài đặt.
     * Người chờ lâu hơn làm chủ phòng (ngồi bên trái).
     */
    private GameRoom openQuickRoom(QuickTicket host, QuickTicket guest, List<DeckPairView> pairs) {
        Instant now = Instant.now();
        GameType type = host.gameType();
        GameSettings initial = initialQuickSettings(type, host, pairs);
        GameRoom room = new GameRoom(UUID.randomUUID().toString().replace("-", "").substring(0, 12), rooms.newCode(),
                type, RoomVisibility.PRIVATE, host.userId(), initial, now, true);
        room.playersByUserId().put(host.userId(), new RoomPlayer(host.userId(), host.seat().displayName(),
                host.seat().avatarUrl(), 0, now));
        room.playersByUserId().put(guest.userId(), new RoomPlayer(guest.userId(), guest.seat().displayName(),
                guest.seat().avatarUrl(), 1, now));
        leaveCurrentRoom(host.userId(), null);
        rooms.register(room);
        rooms.bindUser(host.userId(), room.getRoomId());
        rooms.bindUser(guest.userId(), room.getRoomId());
        room.withLock(() -> {
            room.setStatus(RoomStatus.DRAFTING);
            room.setDraft(new QuickDraft(type, host.userId(), guest.userId(), pairs, initial, now));
            scheduleDraftLocked(room);
            room.touch(now);
        });
        hub.send(host.userId(), ArenaEvent.of(ArenaEventType.QUICK_MATCH_FOUND, room.getRoomId(), null,
                new QuickMatchFoundPayload(room.getRoomId(), type, guest.seat().displayName(), host.setTitle())));
        hub.send(guest.userId(), ArenaEvent.of(ArenaEventType.QUICK_MATCH_FOUND, room.getRoomId(), null,
                new QuickMatchFoundPayload(room.getRoomId(), type, host.seat().displayName(), host.setTitle())));
        broadcastRoom(room);
        log.info("Ghép trận nhanh {}: user {} và {} (bộ {})", type, host.userId(), guest.userId(), host.setId());
        return room;
    }

    /** Cài đặt tạm trước khi tung đồng xu (mọi giá trị sẽ được chọn lại). */
    private static GameSettings initialQuickSettings(GameType type, QuickTicket host, List<DeckPairView> pairs) {
        int need = QuickDraft.minCardsForFields(type);
        DeckPairView pair = pairs.stream()
                .filter(p -> p.questionField() == DeckField.KANJI && p.answerField() == DeckField.MEANING
                        && p.usableCards() >= need)
                .findFirst()
                .orElseGet(() -> pairs.stream().max(Comparator.comparingInt(DeckPairView::usableCards)).orElseThrow());
        return new GameSettings(host.setId(), host.setTitle(), pair.questionField(), pair.answerField(),
                GameRules.normalize(type, PlayMode.VERSUS, null));
    }

    @Override
    public RoomView draftChoose(long userId, String roomId, DraftChoiceRequest request) {
        return draftAction(userId, roomId, request, false);
    }

    @Override
    public RoomView draftPass(long userId, String roomId, DraftChoiceRequest request) {
        return draftAction(userId, roomId, request, true);
    }

    private RoomView draftAction(long userId, String roomId, DraftChoiceRequest request, boolean pass) {
        rateLimiter.check(userId, "room-settings", properties.arena().rateLimit().settingsPerMinute());
        GameRoom room = rooms.require(roomId);
        RoomView view = room.withLock(() -> {
            requireMember(room, userId);
            QuickDraft draft = room.getDraft();
            if (draft == null || room.getStatus() != RoomStatus.DRAFTING) {
                throw new ApiException(ErrorCode.CONFLICT, "Phòng không còn trong lượt chọn cài đặt");
            }
            Instant now = Instant.now();
            String error = pass
                    ? draft.pass(userId, request.stepKey(), now)
                    : draft.choose(userId, request.stepKey(), request.value(), now, ThreadLocalRandom.current());
            if (error != null) {
                throw new ApiException(ErrorCode.CONFLICT, error);
            }
            afterDraftChangeLocked(room, now);
            return mapper.toRoomView(room);
        });
        broadcastRoom(room);
        return view;
    }

    private void onDraftTimer(GameRoom room, int token) {
        boolean changed = room.withLock(() -> {
            QuickDraft draft = room.getDraft();
            if (room.isClosed() || draft == null || room.getStatus() != RoomStatus.DRAFTING || draft.token() != token) {
                return false;
            }
            Instant now = Instant.now();
            draft.onTimer(now, ThreadLocalRandom.current());
            afterDraftChangeLocked(room, now);
            return true;
        });
        if (changed) {
            broadcastRoom(room);
        }
    }

    /** Gọi trong lock: chọn xong hết thì chốt cài đặt và chờ hai bên sẵn sàng, chưa xong thì hẹn bước kế. */
    private void afterDraftChangeLocked(GameRoom room, Instant now) {
        QuickDraft draft = room.getDraft();
        if (draft.isComplete()) {
            GameSettings chosen = draft.settings();
            room.setSettings(chosen.withOptions(GameRules.normalize(room.getGameType(), PlayMode.VERSUS, chosen.options())));
            room.setStatus(RoomStatus.WAITING);
            room.playersByUserId().values().forEach(player -> player.setReady(false));
            if (room.getDraftTask() != null) {
                room.getDraftTask().cancel(false);
            }
            room.setDraftTask(null);
        } else {
            scheduleDraftLocked(room);
        }
        room.touch(now);
    }

    private void scheduleDraftLocked(GameRoom room) {
        QuickDraft draft = room.getDraft();
        if (room.getDraftTask() != null) {
            room.getDraftTask().cancel(false);
        }
        if (draft.phaseEndsAt() == null) {
            room.setDraftTask(null);
            return;
        }
        int token = draft.token();
        room.setDraftTask(scheduler.at(draft.phaseEndsAt(), () -> onDraftTimer(room, token)));
    }

    // -------------------------------------------------------------------- solo

    @Override
    public LaunchView startSolo(long userId, SoloRequest request) {
        rateLimiter.check(userId, "solo-start", properties.arena().rateLimit().startPerMinute());
        GameSettings settings = validatedSettings(request.gameType(), PlayMode.SOLO, request.settings(), userId);
        GameSession session = openSession(request.gameType(), PlayMode.SOLO, null, GameMatchRecorder.SOLO_ROOM_CODE,
                settings, List.of(seatOf(userId, 0)), userId);
        return new LaunchView(session.getSessionId(), session.getGameType(),
                session.getGameType().playUrl(session.getSessionId()));
    }

    private GameSession openSession(GameType type, PlayMode mode, String roomId, String roomCode,
                                    GameSettings settings, List<PlayerSeat> seats, long authorityUserId) {
        GameOptions options = GameRules.normalize(type, mode, settings.options());
        DeckWords deck = decks.loadWords(authorityUserId, settings.setId(), settings.questionField(),
                settings.answerField());
        requireEnoughWords(type, options, deck, settings);
        String sessionId = UUID.randomUUID().toString();
        Long matchId = recorder.start(type, roomId == null ? "solo:" + sessionId : roomId, roomCode, deck.setId(),
                seats, settings.withTitle(deck.title()).withOptions(options));
        LaunchRequest request = new LaunchRequest(sessionId, mode, roomId, roomId == null ? null : roomCode, matchId,
                deck.setId(), deck.title(), settings.questionField(), settings.answerField(), options, seats,
                deck.words());
        return engine(type).launch(request);
    }

    // ----------------------------------------------------------------- session

    @Override
    public SessionInfoView session(long userId, String sessionId) {
        GameSession session = sessions.require(sessionId);
        if (!session.isParticipant(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không tham gia trận này");
        }
        return session.withLock(() -> mapper.toSessionInfo(session, userId));
    }

    @Override
    public void leaveSession(long userId, String sessionId) {
        GameSession session = sessions.require(sessionId);
        if (!session.isParticipant(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không tham gia trận này");
        }
        engine(session.getGameType()).forfeit(session, userId);
    }

    @Override
    public SseEmitter subscribe(long userId) {
        return hub.subscribe(userId);
    }

    // ------------------------------------------------------------------ events

    @EventListener
    public void onSessionFinished(GameSessionFinishedEvent event) {
        if (event.roomId() == null) {
            return;
        }
        rooms.find(event.roomId()).ifPresent(room -> {
            boolean changed = room.withLock(() -> {
                if (!event.sessionId().equals(room.getSessionId())) {
                    return false;
                }
                room.setStatus(RoomStatus.WAITING);
                room.setSessionId(null);
                if (event.summary() != null) {
                    room.setLastResult(event.summary());
                }
                room.playersByUserId().values().forEach(player -> player.setReady(false));
                room.touch(Instant.now());
                return true;
            });
            if (changed) {
                broadcastRoom(room);
            }
        });
    }

    @EventListener
    public void onPresence(ArenaPresenceEvent event) {
        long userId = event.userId();
        for (GameSession session : sessions.all()) {
            if (!session.getStatus().isTerminal() && session.isParticipant(userId)) {
                engine(session.getGameType()).presenceChanged(session, userId, event.online());
            }
        }
        rooms.roomOf(userId).ifPresent(this::broadcastRoom);
        if (!event.online()) {
            scheduler.after(properties.arena().reconnectGraceSeconds() * 1000L, () -> dropIfStillOffline(userId));
        }
    }

    /** Mất kết nối quá thời gian chờ khi đang ở phòng chờ thì rời phòng (đang trong trận thì giữ). */
    private void dropIfStillOffline(long userId) {
        if (hub.isOnline(userId)) {
            return;
        }
        cancelQuickMatch(userId);
        rooms.roomOf(userId).ifPresent(room -> {
            RoomStatus status = room.withLock(room::getStatus);
            if (status != RoomStatus.PLAYING) {
                leave(userId, room.getRoomId());
            }
        });
    }

    private void tick() {
        Instant now = Instant.now();
        for (GameEngine engine : engines.values()) {
            engine.tick(now);
        }
    }

    /** Dọn phòng bỏ không và trận đã xong quá lâu để bộ nhớ không phình ra. */
    private void sweep() {
        Instant now = Instant.now();
        Duration roomIdle = Duration.ofMinutes(properties.arena().roomIdleTimeoutMinutes());
        for (GameRoom room : rooms.all()) {
            boolean stale = room.withLock(() -> room.isClosed()
                    || (room.getStatus() == RoomStatus.WAITING && room.getLastActivityAt().plus(roomIdle).isBefore(now)));
            if (stale) {
                List<Long> members = room.withLock(() -> {
                    room.setClosed(true);
                    return room.memberIds();
                });
                rooms.remove(room);
                hub.send(members, ArenaEvent.of(ArenaEventType.ROOM_CLOSED, room.getRoomId(), null,
                        new RoomClosedPayload("Phòng đã đóng vì lâu không hoạt động")));
            }
        }
        Duration finishedKeep = Duration.ofMinutes(properties.memory().sessionIdleTimeoutMinutes());
        for (GameSession session : sessions.all()) {
            boolean terminal = session.getStatus().isTerminal();
            if (terminal && session.getFinishedAt() != null && session.getFinishedAt().plus(finishedKeep).isBefore(now)) {
                sessions.remove(session);
            } else if (!terminal && session.getLastActivityAt().plus(STALE_RUNNING_SESSION).isBefore(now)) {
                engine(session.getGameType()).abort(session);
            }
        }
        synchronized (queueLock) {
            tickets.values().removeIf(ticket -> ticket.enqueuedAt().plus(QUICK_TICKET_TTL).isBefore(now));
        }
        rateLimiter.purge();
    }

    // ----------------------------------------------------------------- helpers

    /** Chuẩn hoá tuỳ chọn và kiểm bộ thẻ có đủ thẻ mang cả 2 trường đã chọn. */
    private GameSettings validatedSettings(GameType type, PlayMode mode, GameSettings raw, long authorityUserId) {
        if (raw == null || raw.setId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chọn bộ flashcard");
        }
        GameRules.validateFields(raw.questionField(), raw.answerField());
        GameOptions options = GameRules.normalize(type, mode, raw.options());
        DeckWords deck = decks.loadWords(authorityUserId, raw.setId(), raw.questionField(), raw.answerField());
        requireEnoughWords(type, options, deck, raw);
        return new GameSettings(deck.setId(), deck.title(), raw.questionField(), raw.answerField(), options);
    }

    private static void requireEnoughWords(GameType type, GameOptions options, DeckWords deck, GameSettings settings) {
        int needed = GameRules.minWords(type, options);
        if (deck.words().size() < needed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bộ \"" + deck.title() + "\" chỉ có "
                    + deck.words().size() + " thẻ có đủ " + settings.questionField().label() + " và "
                    + settings.answerField().label() + "; "
                    + GameRules.gameName(type) + " với cài đặt này cần ít nhất " + needed + " thẻ.");
        }
    }

    private void leaveCurrentRoom(long userId, String exceptRoomId) {
        Optional<GameRoom> current = rooms.roomOf(userId);
        if (current.isPresent() && !current.get().getRoomId().equals(exceptRoomId)) {
            leave(userId, current.get().getRoomId());
        }
    }

    private void requireHost(GameRoom room, long userId) {
        room.withLock(() -> {
            requireMember(room, userId);
            if (room.getHostUserId() != userId) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Chỉ chủ phòng làm được thao tác này");
            }
        });
    }

    private static RoomPlayer requireMember(GameRoom room, long userId) {
        RoomPlayer player = room.playersByUserId().get(userId);
        if (player == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn chưa ở trong phòng này");
        }
        return player;
    }

    private void broadcastRoom(GameRoom room) {
        record Broadcast(List<Long> members, RoomView view) {
        }
        Broadcast broadcast = room.withLock(() -> room.isClosed() ? null
                : new Broadcast(room.memberIds(), mapper.toRoomView(room)));
        if (broadcast != null) {
            hub.send(broadcast.members(), ArenaEvent.of(ArenaEventType.ROOM_STATE, room.getRoomId(), null,
                    broadcast.view()));
        }
    }

    private void notice(GameRoom room, String message) {
        List<Long> members = room.withLock(room::memberIds);
        hub.send(members, ArenaEvent.of(ArenaEventType.ROOM_NOTICE, room.getRoomId(), null,
                new RoomClosedPayload(message)));
    }

    private GameEngine engine(GameType type) {
        GameEngine engine = engines.get(type);
        if (engine == null) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Game này chưa sẵn sàng");
        }
        return engine;
    }

    private PlayerSeat seatOf(long userId, int slot) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Vui lòng đăng nhập"));
        String name = firstText(user.getFullName(), user.getUsername(),
                user.getEmail() == null ? null : user.getEmail().split("@")[0], "Người chơi");
        return new PlayerSeat(userId, name, user.getAvatarUrl(), slot);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
