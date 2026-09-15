package com.jlas.auth.service;

import com.jlas.auth.dto.*;
import com.jlas.auth.entity.AuthToken;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.mapper.UserMapper;
import com.jlas.auth.repository.AuthTokenRepository;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.service.impl.AuthServiceImpl;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.config.AuthProperties;
import com.jlas.notification.service.UserNotificationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    @Mock UserRepository users;
    @Mock RoleRepository roles;
    @Mock AuthTokenRepository tokens;
    @Mock PasswordEncoder encoder;
    @Mock UserMapper mapper;
    @Mock EmailService emailService;
    @Mock GoogleTokenService googleTokenService;
    @Mock LoginAttemptThrottle throttle;
    @Mock UserNotificationPort userNotificationPort;

    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(users, roles, tokens, encoder,
                new AuthProperties("test-only-secret-with-at-least-thirty-two-characters", 900, 604800),
                mapper, emailService, googleTokenService, throttle, userNotificationPort);
        ReflectionTestUtils.setField(service, "verificationTokenTtl", 900L);
    }

    @Test
    @DisplayName("AUTH-TC-001/AUTH-TC-035: đăng ký chuẩn hóa email, hash mật khẩu và gán LEARNER")
    void registerPersistsNormalizedSecureLearnerAccount() {
        RegisterRequest request = registerRequest("  NEW.User@Example.COM  ");
        Role learner = Role.builder().id(2L).name("LEARNER").build();
        when(users.findByEmail("new.user@example.com")).thenReturn(Optional.empty());
        when(roles.findByName("LEARNER")).thenReturn(Optional.of(learner));
        when(encoder.encode("Valid@123")).thenReturn("bcrypt-hash");
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        when(tokens.findByUser_IdAndTokenTypeAndUsedAtIsNullAndRevokedAtIsNull(eq(10L), eq("VERIFY_EMAIL")))
                .thenReturn(List.of());

        service.register(request);

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(users).save(user.capture());
        assertThat(user.getValue().getEmail()).isEqualTo("new.user@example.com");
        assertThat(user.getValue().getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(user.getValue().getRole().getName()).isEqualTo("LEARNER");
        assertThat(user.getValue().getIsVerified()).isFalse();
        verify(emailService).sendVerificationEmail(eq("new.user@example.com"), eq("Nguyen Van An"), matches("\\d{6}"));
    }

    @Test
    @DisplayName("AUTH-TC-012: không tạo tài khoản khi email đã xác minh tồn tại")
    void registerRejectsExistingVerifiedEmail() {
        User existing = user(1L, true, "hash");
        when(users.findByEmail("learner@example.com")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.register(registerRequest("learner@example.com")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("already registered");
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("AUTH-TC-012b: email chưa xác minh trả PENDING_VERIFICATION thay vì tạo tài khoản mới")
    void registerRejectsExistingUnverifiedEmailWithPendingStatus() {
        // Fix #3: thêm test cho nhánh PENDING_VERIFICATION
        User existing = user(1L, false, "hash");
        when(users.findByEmail("learner@example.com")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.register(registerRequest("learner@example.com")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.PENDING_VERIFICATION);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("AUTH-TC-039/AUTH-TC-053: đăng nhập đúng phát hành access và refresh token")
    void loginIssuesTokensForVerifiedAccount() {
        User user = user(7L, true, "bcrypt-hash");
        when(users.findByEmail("learner@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("Valid@123", "bcrypt-hash")).thenReturn(true);
        when(mapper.toResponse(user)).thenReturn(UserResponse.builder().id(7L).email(user.getEmail()).build());

        LoginResponse response = service.login(loginRequest(" LEARNER@EXAMPLE.COM ", "Valid@123"), "127.0.0.1");

        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(throttle).recordSuccess("learner@example.com", "127.0.0.1");
        verify(tokens).save(argThat(t -> "REFRESH".equals(t.getTokenType()) && t.getTokenHash().length() == 64));
    }

    @Test
    @DisplayName("AUTH-TC-045: mật khẩu sai trả unauthorized và ghi nhận thất bại")
    void loginRejectsWrongPassword() {
        User user = user(7L, true, "bcrypt-hash");
        when(users.findByEmail("learner@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches("Wrong@123", "bcrypt-hash")).thenReturn(false);
        assertThatThrownBy(() -> service.login(loginRequest("learner@example.com", "Wrong@123"), "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Invalid email or password");
        verify(throttle).recordFailure("learner@example.com", "127.0.0.1");
    }

    @Test
    @DisplayName("AUTH-TC-049: tài khoản chưa xác minh không được đăng nhập, nhưng throttle vẫn được clear")
    void loginRejectsUnverifiedAccountButClearsThrottle() {
        // Fix #4: verify throttle.recordSuccess được gọi trước khi throw (vì check isVerified sau check password)
        User user = user(7L, false, "bcrypt-hash");
        when(users.findByEmail("learner@example.com")).thenReturn(Optional.of(user));
        when(encoder.matches(anyString(), anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.login(loginRequest("learner@example.com", "Valid@123"), "127.0.0.1"))
                .isInstanceOf(ApiException.class).hasMessageContaining("not verified");
        // throttle.recordSuccess phải được gọi vì password đúng trước khi fail ở isVerified
        verify(throttle).recordSuccess("learner@example.com", "127.0.0.1");
        verify(throttle, never()).recordFailure(any(), any());
    }

    @Test
    @DisplayName("AUTH-TC-069: forgot password không tiết lộ email chưa đăng ký")
    void forgotPasswordDoesNothingObservableForUnknownEmail() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("missing@example.com");
        service.forgotPassword(request);
        verifyNoInteractions(emailService);
        verify(tokens, never()).save(any());
    }

    @Test
    @DisplayName("AUTH-TC-068/AUTH-TC-085: forgot password lưu OTP dạng hash và gửi OTP 6 số")
    void forgotPasswordStoresOnlyOtpHash() {
        User user = user(9L, true, "hash");
        when(users.findByEmail("learner@example.com")).thenReturn(Optional.of(user));
        service.forgotPassword(forgotRequest(" LEARNER@EXAMPLE.COM "));
        // Fix #5: verify token cũ bị xóa trước khi tạo mới
        verify(tokens).deleteByUser_IdAndTokenType(9L, "RESET_PASSWORD");
        ArgumentCaptor<AuthToken> saved = ArgumentCaptor.forClass(AuthToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getOtpHash()).hasSize(64).doesNotMatch("\\d{6}");
        verify(emailService).sendPasswordResetOtp(eq("learner@example.com"), any(), matches("\\d{6}"));
    }

    @Test
    @DisplayName("PROF-TC-011/PROF-TC-014: profile chỉ cập nhật field cho phép")
    void updateProfileChangesOnlyAllowedFields() {
        User user = user(11L, true, "hash");
        Role originalRole = user.getRole();
        when(users.findById(11L)).thenReturn(Optional.of(user));
        when(users.save(user)).thenReturn(user);
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setFullName("  Nguyen Van B  ");
        request.setPhoneNumber(" 0912345678 ");
        request.setGender("MALE");
        request.setJapaneseLevel("N3");

        service.updateProfile(11L, request);

        assertThat(user.getFullName()).isEqualTo("Nguyen Van B");
        assertThat(user.getPhoneNumber()).isEqualTo("0912345678");
        assertThat(user.getRole()).isSameAs(originalRole);
        assertThat(user.getEmail()).isEqualTo("learner@example.com");
    }

    @Test
    @DisplayName("PROF-TC-033: đổi mật khẩu từ chối mật khẩu hiện tại sai")
    void changePasswordRejectsWrongCurrentPassword() {
        User user = user(12L, true, "old-hash");
        when(users.findById(12L)).thenReturn(Optional.of(user));
        when(encoder.matches("Wrong@123", "old-hash")).thenReturn(false);
        assertThatThrownBy(() -> service.changePassword(12L, changeRequest("Wrong@123", "NewValid@123")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Current password is incorrect");
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("PROF-TC-031/PROF-TC-040: đổi mật khẩu hash giá trị mới và thu hồi refresh token")
    void changePasswordPersistsHashAndRevokesRefreshTokens() {
        User user = user(12L, true, "old-hash");
        when(users.findById(12L)).thenReturn(Optional.of(user));
        when(encoder.matches("Current@123", "old-hash")).thenReturn(true);
        when(encoder.matches("NewValid@123", "old-hash")).thenReturn(false);
        when(encoder.encode("NewValid@123")).thenReturn("new-hash");
        service.changePassword(12L, changeRequest("Current@123", "NewValid@123"));
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(tokens).deleteByUser_IdAndTokenType(12L, "REFRESH");
    }

    @Test
    @DisplayName("AUTH-TC-059/AUTH-TC-065: Google user mới nhận LEARNER và được xác minh")
    void googleLoginCreatesVerifiedLearner() {
        GoogleLoginRequest request = new GoogleLoginRequest(); request.setIdToken("stub-token");
        when(googleTokenService.verify("stub-token")).thenReturn(
                new GoogleTokenService.GoogleUserInfo("google-1", "NEW@EXAMPLE.COM", "Google User", "https://example.com/a.png"));
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(roles.findByName("LEARNER")).thenReturn(Optional.of(Role.builder().name("LEARNER").build()));
        service.loginWithGoogle(request);
        verify(users).save(argThat(u -> Boolean.TRUE.equals(u.getIsVerified())
                && "LEARNER".equals(u.getRole().getName()) && "new@example.com".equals(u.getEmail())));
    }

    private static RegisterRequest registerRequest(String email) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email); request.setPassword("Valid@123"); request.setConfirmPassword("Valid@123");
        request.setFullName("Nguyen Van An"); request.setJapaneseLevel("N5");
        return request;
    }
    private static LoginRequest loginRequest(String email, String password) {
        LoginRequest request = new LoginRequest(); request.setEmail(email); request.setPassword(password); return request;
    }
    private static ForgotPasswordRequest forgotRequest(String email) {
        ForgotPasswordRequest request = new ForgotPasswordRequest(); request.setEmail(email); return request;
    }
    private static ChangePasswordRequest changeRequest(String current, String next) {
        ChangePasswordRequest request = new ChangePasswordRequest(); request.setCurrentPassword(current);
        request.setPassword(next); request.setConfirmPassword(next); return request;
    }
    private static User user(Long id, boolean verified, String hash) {
        return User.builder().id(id).email("learner@example.com").passwordHash(hash).isVerified(verified)
                .role(Role.builder().id(2L).name("LEARNER").build()).loginType("LOCAL").creditBalance(0).build();
    }
}
