package com.jlas.auth.repository;

import com.jlas.auth.entity.AuthToken;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository tests chạy trên H2 in-memory (jlas_repo_test) riêng biệt với
 * các @SpringBootTest khác (jlas_test) để tránh race condition create-drop.
 *
 * Dùng @SpringBootTest(webEnvironment=NONE) thay @DataJpaTest vì Spring Boot 4
 * slice test không áp dụng ddl-auto từ application-test.yml đúng cách.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:jlas_repo_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class AuthRepositoryTest {
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AuthTokenRepository tokens;

    @Test
    @DisplayName("AUTH-TC-012: email unique và truy xuất không phân biệt sau khi service normalize")
    void findsPersistedUserByNormalizedEmail() {
        User user = persistUser("learner@example.com");
        assertThat(users.findByEmail("learner@example.com")).contains(user);
        assertThat(users.existsByEmail("learner@example.com")).isTrue();
    }

    @Test
    @DisplayName("AUTH-TC-074/AUTH-TC-077: query OTP chỉ trả token chưa dùng và chưa revoke")
    void resetOtpQueryExcludesUsedToken() {
        User user = persistUser("reset@example.com");
        AuthToken token = tokens.save(AuthToken.builder().user(user).tokenType("RESET_PASSWORD")
                .tokenHash("a".repeat(64)).otpHash("b".repeat(64))
                .expiresAt(LocalDateTime.now().plusMinutes(15)).build());
        assertThat(tokens.findByTokenTypeAndOtpHashAndUser_EmailAndUsedAtIsNullAndRevokedAtIsNull(
                "RESET_PASSWORD", "b".repeat(64), "reset@example.com")).contains(token);
        token.setUsedAt(LocalDateTime.now());
        tokens.save(token);
        assertThat(tokens.findByTokenTypeAndOtpHashAndUser_EmailAndUsedAtIsNullAndRevokedAtIsNull(
                "RESET_PASSWORD", "b".repeat(64), "reset@example.com")).isEmpty();
    }

    @Test
    @DisplayName("PROF-TC-040: xóa toàn bộ refresh token của user sau đổi mật khẩu")
    void deletesRefreshTokensByUserAndType() {
        User user = persistUser("change@example.com");
        tokens.save(AuthToken.builder().user(user).tokenType("REFRESH").tokenHash("c".repeat(64))
                .expiresAt(LocalDateTime.now().plusDays(7)).build());
        tokens.deleteByUser_IdAndTokenType(user.getId(), "REFRESH");
        assertThat(tokens.findByTokenHash("c".repeat(64))).isEmpty();
    }

    @Test
    @DisplayName("RESEND-TC: query token gần nhất đúng loại theo thứ tự createdAt giảm dần")
    void findsLatestVerificationTokenByEmailAndType() throws Exception {
        // Fix #8: test cho findFirstByUser_EmailAndTokenTypeOrderByCreatedAtDesc
        User user = persistUser("latest@example.com");
        tokens.save(AuthToken.builder().user(user).tokenType("VERIFY_EMAIL")
                .tokenHash("o".repeat(64)).otpHash("p".repeat(64))
                .expiresAt(LocalDateTime.now().plusMinutes(15)).build());
        Thread.sleep(20); // đảm bảo createdAt khác nhau
        AuthToken newer = tokens.save(AuthToken.builder().user(user).tokenType("VERIFY_EMAIL")
                .tokenHash("n".repeat(64)).otpHash("q".repeat(64))
                .expiresAt(LocalDateTime.now().plusMinutes(15)).build());
        var result = tokens.findFirstByUser_EmailAndTokenTypeOrderByCreatedAtDesc("latest@example.com", "VERIFY_EMAIL");
        assertThat(result).isPresent();
        assertThat(result.get().getTokenHash()).isEqualTo("n".repeat(64));
    }

    private User persistUser(String email) {
        Role role = roles.findByName("LEARNER").orElseGet(() -> roles.save(
                Role.builder().name("LEARNER").description("Learner").build()));
        return users.save(User.builder().role(role).email(email).passwordHash("hash")
                .loginType("LOCAL").creditBalance(0).isVerified(true).build());
    }
}
