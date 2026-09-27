package com.jlas.game.runtime;

import java.time.Instant;

/** Một người chơi đang ngồi trong phòng chờ. Chỉ sửa khi đang giữ lock của {@link GameRoom}. */
public class RoomPlayer {

    private final long userId;
    private final String displayName;
    private final String avatarUrl;
    private final int slot;
    private final Instant joinedAt;
    private boolean ready;

    public RoomPlayer(long userId, String displayName, String avatarUrl, int slot, Instant joinedAt) {
        this.userId = userId;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.slot = slot;
        this.joinedAt = joinedAt;
    }

    public long getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public int getSlot() {
        return slot;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public PlayerSeat toSeat() {
        return new PlayerSeat(userId, displayName, avatarUrl, slot);
    }
}
