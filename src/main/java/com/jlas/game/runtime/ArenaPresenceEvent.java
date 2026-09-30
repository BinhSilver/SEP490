package com.jlas.game.runtime;

/** Người chơi vừa mở kết nối SSE đầu tiên ({@code online}) hoặc vừa đóng kết nối cuối cùng. */
public record ArenaPresenceEvent(long userId, boolean online) {
}
