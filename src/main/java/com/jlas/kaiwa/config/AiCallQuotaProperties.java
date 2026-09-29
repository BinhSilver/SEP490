package com.jlas.kaiwa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Hạn mức và phí AI Call (JLAS-W0199). Giữ ở cấu hình cho tới khi team chốt con số.
 *
 * @param freeDailyMinutes       số phút/ngày cho tài khoản thường (bản dùng thử)
 * @param freeMonthlyMinutes     số phút/tháng cho tài khoản thường
 * @param premiumDailyMinutes    số phút/ngày cho Premium còn hạn
 * @param premiumMonthlyMinutes  số phút/tháng cho Premium; 0 = không giới hạn
 * @param minutesPerTurn         số phút ước tính cho một lượt chat
 * @param creditPerTurn          số CP cho một lượt khi đã hết hạn mức và người học xác nhận dùng CP
 */
@ConfigurationProperties(prefix = "aicall.quota")
public record AiCallQuotaProperties(
        @DefaultValue("5") double freeDailyMinutes,
        @DefaultValue("300") double freeMonthlyMinutes,
        @DefaultValue("30") double premiumDailyMinutes,
        @DefaultValue("0") double premiumMonthlyMinutes,
        @DefaultValue("0.2") double minutesPerTurn,
        @DefaultValue("5") int creditPerTurn) {
}
