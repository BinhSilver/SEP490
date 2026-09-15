package com.jlas.auth.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {
    private static final String SECRET = "test-only-secret-with-at-least-thirty-two-characters";

    @Test
    @DisplayName("AUTH-TC-053: token hợp lệ chứa đúng email và chữ ký")
    void generatedTokenCanBeVerifiedAndRead() {
        String token = JwtUtil.generateToken("learner@example.com", SECRET, 60);
        assertThat(JwtUtil.isTokenValid(token, SECRET)).isTrue();
        assertThat(JwtUtil.extractEmail(token, SECRET)).isEqualTo("learner@example.com");
    }

    @Test
    @DisplayName("AUTH-TC-057: token bị sửa đổi bị từ chối")
    void tamperedTokenIsRejected() {
        String token = JwtUtil.generateToken("learner@example.com", SECRET, 60);
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
        assertThat(JwtUtil.isTokenValid(tampered, SECRET)).isFalse();
    }

    @Test
    @DisplayName("AUTH-TC-056: token hết hạn bị từ chối")
    void expiredTokenIsRejected() {
        String token = JwtUtil.generateToken("learner@example.com", SECRET, -1);
        assertThat(JwtUtil.isTokenValid(token, SECRET)).isFalse();
    }
}
