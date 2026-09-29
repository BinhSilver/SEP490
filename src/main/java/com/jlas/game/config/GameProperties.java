package com.jlas.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tham số Game Arena (phòng 2 người, SSE, luật từng game). Khoá nằm dưới {@code game.*}
 * trong application-local.yaml; thiếu khoá nào thì dùng giá trị mặc định ở đây.
 */
@ConfigurationProperties(prefix = "game")
public record GameProperties(@DefaultValue Arena arena, @DefaultValue Memory memory) {

    /**
     * @param countdownSeconds       đếm ngược trước khi vào trận
     * @param reconnectGraceSeconds  mất kết nối quá chừng này thì bị đưa ra khỏi phòng chờ
     * @param roomIdleTimeoutMinutes phòng không hoạt động quá lâu sẽ bị dọn
     * @param maxRooms               số phòng tối đa giữ trong bộ nhớ
     * @param sweepIntervalMs        chu kỳ dọn phòng/trận cũ
     */
    public record Arena(
            @DefaultValue("3") int countdownSeconds,
            @DefaultValue("25") int reconnectGraceSeconds,
            @DefaultValue("30") int roomIdleTimeoutMinutes,
            @DefaultValue("5000") int maxRooms,
            @DefaultValue("15000") long sweepIntervalMs,
            @DefaultValue RateLimit rateLimit) {
    }

    /** Giới hạn thao tác mỗi phút cho từng người chơi. */
    public record RateLimit(
            @DefaultValue("40") int readyPerMinute,
            @DefaultValue("60") int settingsPerMinute,
            @DefaultValue("15") int startPerMinute,
            @DefaultValue("30") int joinPerMinute,
            @DefaultValue("120") int flipPerMinute,
            @DefaultValue("180") int answerPerMinute) {
    }

    /**
     * @param revealDelayMs             thời gian giữ hai thẻ sai trước khi úp lại
     * @param sessionIdleTimeoutMinutes trận đã xong được giữ để xem kết quả trong chừng này phút
     * @param maxSessions               số trận tối đa giữ trong bộ nhớ
     */
    public record Memory(
            @DefaultValue("900") int revealDelayMs,
            @DefaultValue("20") int sessionIdleTimeoutMinutes,
            @DefaultValue("5000") int maxSessions) {
    }
}
