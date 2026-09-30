package com.jlas.game.runtime;

/** Người chơi của một trận (ảnh chụp lúc vào trận). */
public record PlayerSeat(long userId, String displayName, String avatarUrl, int slot) {
}
