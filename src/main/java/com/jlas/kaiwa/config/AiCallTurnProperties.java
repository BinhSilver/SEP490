package com.jlas.kaiwa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Xử lý lượt chat AI Call (JLAS-W0199).
 *
 * @param maxConcurrentTurns     số lượt gọi AI chạy đồng thời trên một máy chủ
 * @param queueWaitSeconds       thời gian một lượt hợp lệ được xếp hàng (PENDING) chờ tới lượt;
 *                               quá hạn thì báo quá tải và hoàn lại, không trừ
 * @param pendingTimeoutSeconds  lượt PENDING quá lâu (máy chủ tắt giữa chừng) bị job dọn dẹp đánh FAILED và hoàn lại
 */
@ConfigurationProperties(prefix = "aicall.turn")
public record AiCallTurnProperties(
        @DefaultValue("20") int maxConcurrentTurns,
        @DefaultValue("15") int queueWaitSeconds,
        @DefaultValue("180") int pendingTimeoutSeconds) {
}
