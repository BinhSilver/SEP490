package com.jlas.game.service;

import com.jlas.game.enums.GameType;
import com.jlas.game.runtime.GameSession;
import com.jlas.game.runtime.LaunchRequest;

import java.time.Instant;

/** Luật của một game. Phòng chờ gọi {@link #launch} khi đếm ngược xong; bộ đếm giờ gọi {@link #tick}. */
public interface GameEngine {

    GameType type();

    GameSession launch(LaunchRequest request);

    /** Người chơi rời trận: đối kháng thì đối thủ thắng ngay, chơi đơn thì kết thúc trận. */
    void forfeit(GameSession session, long userId);

    void presenceChanged(GameSession session, long userId, boolean online);

    /** Chạy mỗi giây: kết thúc lượt/trận đã quá giờ. */
    void tick(Instant now);

    /** Dừng trận không ghi người thắng (phòng bị đóng, trận bị bỏ quá lâu). */
    void abort(GameSession session);
}
