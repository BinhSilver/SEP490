package com.jlas.game.runtime;

import com.jlas.game.dto.GameDtos.ResultSummary;

/** Engine phát khi một trận kết thúc để phòng quay về trạng thái chờ đấu tiếp. */
public record GameSessionFinishedEvent(String sessionId, String roomId, ResultSummary summary) {
}
