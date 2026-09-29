package com.jlas.game.enums;

/** Trạng thái một trận đang giữ trong bộ nhớ server. */
public enum SessionStatus {
    RUNNING,
    PAUSED,
    FINISHED,
    ABORTED;

    public boolean isTerminal() {
        return this == FINISHED || this == ABORTED;
    }
}
