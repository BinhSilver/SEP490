package com.jlas.game.runtime;

import com.jlas.game.dto.GameDtos.GameSettings;
import com.jlas.game.dto.GameDtos.ResultSummary;
import com.jlas.game.enums.GameType;
import com.jlas.game.enums.RoomStatus;
import com.jlas.game.enums.RoomVisibility;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Phòng đối kháng 2 người, chỉ tồn tại trong bộ nhớ server. Mọi thay đổi phải chạy trong
 * {@link #withLock} vì thao tác của hai người chơi và bộ đếm giờ có thể tới cùng lúc.
 */
public class GameRoom {

    public static final int MAX_PLAYERS = 2;

    private final ReentrantLock lock = new ReentrantLock();
    private final String roomId;
    private final String code;
    private final GameType gameType;
    private final RoomVisibility visibility;
    private final Instant createdAt;
    /** Phòng do ghép trận nhanh tạo ra (không cần mời bằng mã). */
    private final boolean quickMatch;
    /** userId → player, giữ thứ tự vào phòng để chuyển chủ phòng xác định. */
    private final Map<Long, RoomPlayer> players = new LinkedHashMap<>();

    private long hostUserId;
    private RoomStatus status = RoomStatus.WAITING;
    private GameSettings settings;
    private Instant lastActivityAt;
    private Instant countdownEndsAt;
    private ScheduledFuture<?> countdownTask;
    private String sessionId;
    private ResultSummary lastResult;
    private boolean closed;
    private long version;
    /** Chỉ có ở phòng ghép trận nhanh: tung đồng xu chọn từng cài đặt. */
    private QuickDraft draft;
    private ScheduledFuture<?> draftTask;

    public GameRoom(String roomId, String code, GameType gameType, RoomVisibility visibility, long hostUserId,
                    GameSettings settings, Instant createdAt, boolean quickMatch) {
        this.roomId = roomId;
        this.quickMatch = quickMatch;
        this.code = code;
        this.gameType = gameType;
        this.visibility = visibility;
        this.hostUserId = hostUserId;
        this.settings = settings;
        this.createdAt = createdAt;
        this.lastActivityAt = createdAt;
    }

    public <T> T withLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public String getRoomId() {
        return roomId;
    }

    public String getCode() {
        return code;
    }

    public GameType getGameType() {
        return gameType;
    }

    public RoomVisibility getVisibility() {
        return visibility;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isQuickMatch() {
        return quickMatch;
    }

    public long getHostUserId() {
        return hostUserId;
    }

    public void setHostUserId(long hostUserId) {
        this.hostUserId = hostUserId;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    public GameSettings getSettings() {
        return settings;
    }

    public void setSettings(GameSettings settings) {
        this.settings = settings;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public Instant getCountdownEndsAt() {
        return countdownEndsAt;
    }

    public void setCountdownEndsAt(Instant countdownEndsAt) {
        this.countdownEndsAt = countdownEndsAt;
    }

    public ScheduledFuture<?> getCountdownTask() {
        return countdownTask;
    }

    public void setCountdownTask(ScheduledFuture<?> countdownTask) {
        this.countdownTask = countdownTask;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public ResultSummary getLastResult() {
        return lastResult;
    }

    public void setLastResult(ResultSummary lastResult) {
        this.lastResult = lastResult;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public QuickDraft getDraft() {
        return draft;
    }

    public void setDraft(QuickDraft draft) {
        this.draft = draft;
    }

    public ScheduledFuture<?> getDraftTask() {
        return draftTask;
    }

    public void setDraftTask(ScheduledFuture<?> draftTask) {
        this.draftTask = draftTask;
    }

    public long getVersion() {
        return version;
    }

    /** Ghi nhận một thay đổi: tăng version để client bỏ snapshot cũ, cập nhật mốc hoạt động. */
    public void touch(Instant now) {
        version++;
        lastActivityAt = now;
    }

    public Map<Long, RoomPlayer> playersByUserId() {
        return players;
    }

    public List<RoomPlayer> orderedPlayers() {
        List<RoomPlayer> ordered = new ArrayList<>(players.values());
        ordered.sort(Comparator.comparingInt(RoomPlayer::getSlot));
        return ordered;
    }

    public List<Long> memberIds() {
        return new ArrayList<>(players.keySet());
    }

    public boolean isFull() {
        return players.size() >= MAX_PLAYERS;
    }

    public int freeSlot() {
        for (int slot = 0; slot < MAX_PLAYERS; slot++) {
            final int candidate = slot;
            if (players.values().stream().noneMatch(player -> player.getSlot() == candidate)) {
                return slot;
            }
        }
        return -1;
    }
}
