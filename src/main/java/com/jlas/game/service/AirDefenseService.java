package com.jlas.game.service;

import com.jlas.game.dto.AirDefenseDtos.AirAttackResult;
import com.jlas.game.dto.AirDefenseDtos.AirKillResult;
import com.jlas.game.dto.AirDefenseDtos.AirReportRequest;
import com.jlas.game.dto.AirDefenseDtos.AirStartRequest;
import com.jlas.game.dto.AirDefenseDtos.AirStateView;

/** Thao tác của người chơi trong một trận Air Defence. */
public interface AirDefenseService extends GameEngine {

    AirStateView state(String sessionId, long userId);

    /** Luyện tập: áp cài đặt (hỏi/đáp, thẻ, thời gian) rồi bắt đầu đếm ngược. */
    AirStateView start(String sessionId, long userId, AirStartRequest request);

    /** Kiểm lại một lần bắn hạ: chuỗi đã gõ có đúng đáp án của mục tiêu không. */
    AirKillResult kill(String sessionId, long userId, String wordId, String rawInput);

    /** Client báo HP/điểm/combo/đợt hiện tại để đối thủ theo dõi. */
    AirStateView report(String sessionId, long userId, AirReportRequest request);

    /** Đạt mốc combo: gửi một mini-boss sang hành tinh đối thủ. */
    AirAttackResult attack(String sessionId, long userId, int milestone);

    /** Hành tinh về 0 HP hoặc người chơi dừng (chơi đơn). */
    AirStateView finish(String sessionId, long userId, AirReportRequest finalReport, boolean defeated);
}
