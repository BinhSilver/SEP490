package com.jlas.game.service;

import com.jlas.game.dto.MemoryDtos.MemoryStartRequest;
import com.jlas.game.dto.MemoryDtos.MemoryStateView;

/** Thao tác của người chơi trong một ván Memory Match. */
public interface MemoryMatchService extends GameEngine {

    MemoryStateView state(String sessionId, long userId);

    /** Ý định lật một thẻ; hợp lệ hay không, khớp cặp hay không đều do server quyết định. */
    MemoryStateView flip(String sessionId, long userId, String cardInstanceId);

    MemoryStateView setPaused(String sessionId, long userId, boolean paused);

    /** Luyện tập: người chơi bấm "Bắt đầu" (kèm cài đặt đã chọn), đếm ngược 3 giây rồi vào ván. */
    MemoryStateView start(String sessionId, long userId, MemoryStartRequest request);
}
