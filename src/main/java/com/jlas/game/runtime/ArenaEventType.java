package com.jlas.game.runtime;

/** Tên sự kiện SSE gửi tới trình duyệt (trường {@code type} của ArenaEvent). */
public final class ArenaEventType {
    public static final String HELLO = "HELLO";
    public static final String ROOM_STATE = "ROOM_STATE";
    public static final String ROOM_STARTED = "ROOM_STARTED";
    public static final String ROOM_CLOSED = "ROOM_CLOSED";
    public static final String ROOM_NOTICE = "ROOM_NOTICE";
    public static final String QUICK_MATCH_FOUND = "QUICK_MATCH_FOUND";
    public static final String SESSION_PRESENCE = "SESSION_PRESENCE";
    public static final String SESSION_FINISHED = "SESSION_FINISHED";
    public static final String NEKO_PROGRESS = "NEKO_PROGRESS";
    public static final String MEMORY_STATE = "MEMORY_STATE";
    public static final String AIR_PROGRESS = "AIR_PROGRESS";
    public static final String AIR_ATTACK = "AIR_ATTACK";

    private ArenaEventType() { }
}
