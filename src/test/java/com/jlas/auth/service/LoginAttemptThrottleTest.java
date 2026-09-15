package com.jlas.auth.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptThrottleTest {
    @Test
    @DisplayName("AUTH-TC-050: chặn lần thử tiếp theo sau 5 đăng nhập sai cùng email và IP")
    void blocksAfterFiveFailures() {
        LoginAttemptThrottle throttle = new LoginAttemptThrottle();
        for (int i = 0; i < 5; i++) throttle.recordFailure("learner@example.com", "127.0.0.1");
        assertThatThrownBy(() -> throttle.assertAllowed("learner@example.com", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("AUTH-TC-050: đăng nhập thành công xóa trạng thái throttling")
    void successClearsFailureState() {
        LoginAttemptThrottle throttle = new LoginAttemptThrottle();
        for (int i = 0; i < 5; i++) throttle.recordFailure("learner@example.com", "127.0.0.1");
        throttle.recordSuccess("learner@example.com", "127.0.0.1");
        assertThatCode(() -> throttle.assertAllowed("learner@example.com", "127.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AUTH-TC-050: throttle được tách biệt theo IP")
    void failuresAreIsolatedByClientIp() {
        LoginAttemptThrottle throttle = new LoginAttemptThrottle();
        for (int i = 0; i < 5; i++) throttle.recordFailure("learner@example.com", "127.0.0.1");
        assertThatCode(() -> throttle.assertAllowed("learner@example.com", "127.0.0.2")).doesNotThrowAnyException();
    }
}
