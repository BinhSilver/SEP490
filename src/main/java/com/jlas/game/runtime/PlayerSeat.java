package com.jlas.game.runtime;

/**
 * Người chơi của một trận (ảnh chụp lúc vào trận). {@code avatarFrame}/{@code nameStyle}: khung avatar + màu tên
 * đang dùng lúc vào phòng (null = không có), chỉ để trang trí.
 */
public record PlayerSeat(long userId, String displayName, String avatarUrl, int slot,
                         String avatarFrame, String nameStyle) {

    public PlayerSeat(long userId, String displayName, String avatarUrl, int slot) {
        this(userId, displayName, avatarUrl, slot, null, null);
    }
}
