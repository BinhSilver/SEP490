package com.jlas.game.service;

import com.jlas.game.dto.NekoDtos.NekoActionResult;
import com.jlas.game.dto.NekoDtos.NekoStartRequest;
import com.jlas.game.dto.NekoDtos.NekoStateView;

/** Thao tác của người chơi trong một trận Neko Catch. */
public interface NekoCatchService extends GameEngine {

    NekoStateView state(String sessionId, long userId);
    NekoStateView state(String sessionId, long userId, int choiceLimit);

    /** Mèo bắt trúng một con cá (đúng hoặc sai do server quyết định). */
    NekoActionResult catchChoice(String sessionId, long userId, String roundId, String choiceId);

    /** Cá chạm đất trước khi bắt được con đúng. */
    NekoActionResult miss(String sessionId, long userId, String roundId);

    NekoActionResult bonus(String sessionId, long userId, String roundId);

    /** Client báo hết giờ; server chỉ kết thúc nếu đồng hồ của server cũng đã hết. */
    NekoStateView timeUp(String sessionId, long userId);

    NekoStateView setPaused(String sessionId, long userId, boolean paused);

    /**
     * Luyện tập: người chơi bấm "Bắt đầu" (kèm cài đặt đã chọn ở màn chuẩn bị), đếm ngược 3 giây rồi vào trận.
     * Gọi lại khi đã bắt đầu thì bỏ qua.
     */
    NekoStateView start(String sessionId, long userId, NekoStartRequest request);
}
