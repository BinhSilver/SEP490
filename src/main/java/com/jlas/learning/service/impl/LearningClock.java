package com.jlas.learning.service.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108][MP08] Nguồn "bây giờ" theo {@code Asia/Ho_Chi_Minh} cho các ranh giới theo ngày
 * (quota quiz/gate). Test thay {@link #setClock} bằng {@code Clock.fixed(...)} để kiểm đúng một
 * khoảnh khắc quanh 00:00 giờ VN, không đổi timezone JVM toàn cục.
 */
@Component
public class LearningClock {
    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private Clock clock = Clock.system(VN_ZONE);

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
