package com.jlas.auth.service.impl;

import com.jlas.auth.dto.LoginRequest;
import com.jlas.auth.dto.LoginResponse;
import com.jlas.auth.dto.RefreshTokenRequest;
import com.jlas.auth.dto.RegisterRequest;
import com.jlas.auth.dto.UpdateProfileRequest;
import com.jlas.auth.dto.UserResponse;
import com.jlas.auth.dto.VerifyEmailRequest;
import com.jlas.auth.dto.ResendVerificationRequest;
import com.jlas.auth.dto.ChangePasswordRequest;
import com.jlas.auth.dto.GoogleLoginRequest;
import com.jlas.auth.dto.ForgotPasswordRequest;
import com.jlas.auth.dto.ResetPasswordRequest;
import com.jlas.auth.dto.VerifyPasswordResetOtpRequest;
import com.jlas.auth.entity.AuthToken;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.enums.LoginType;
import com.jlas.auth.mapper.UserMapper;
import com.jlas.auth.repository.AuthTokenRepository;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.service.AuthService;
import com.jlas.auth.service.EmailService;
import com.jlas.auth.service.GoogleTokenService;
import com.jlas.auth.service.LoginAttemptThrottle;
import com.jlas.auth.service.PasswordResetService;
import com.jlas.notification.service.UserNotificationPort;
import com.jlas.auth.util.JwtUtil;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.util.BirthDates;
import com.jlas.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {
    private static final String REFRESH = "REFRESH";
    private static final String VERIFY_EMAIL = "VERIFY_EMAIL";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int VERIFICATION_RESEND_LIMIT_PER_HOUR = 5;
    private static final int VERIFICATION_RESEND_COOLDOWN_MINUTES = 3;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;
    private final UserMapper userMapper;
    private final EmailService emailService;
    private final GoogleTokenService googleTokenService;
    private final LoginAttemptThrottle loginAttemptThrottle;
    private final PasswordResetService passwordResets;
    private final UserNotificationPort notifications;
    private final com.jlas.auth.service.AvatarUploadService avatarUploads;
    private final com.jlas.moderation.service.ContentModerationPort moderation;

    @Value("${jlas.auth.verification-token-ttl:900}")
    private long verificationTokenTtl;

    @Override
    public LoginResponse login(LoginRequest request, String clientIp) {
        return login(request, clientIp, null);
    }

    @Override
    public LoginResponse login(LoginRequest request, String clientIp, String deviceInfo) {
        String email = normalizeEmail(request.getEmail());
        loginAttemptThrottle.assertAllowed(email, clientIp);
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            throw unauthorized("Invalid email or password");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw unauthorized("Invalid email or password");
        }
        assertUnlocked(user);
        if (!Boolean.TRUE.equals(user.getIsVerified())) {
            throw new ApiException(ErrorCode.EMAIL_UNVERIFIED,
                    "Email is not verified. Please verify your email before logging in.");
        }
        loginAttemptThrottle.recordSuccess(email, clientIp);
        boolean firstLogin = user.getLastLoginAt() == null;
        user.setLastLoginAt(LocalDateTime.now());
        notifications.greetDaily(user.getId(), user.getFullName(), firstLogin);
        return issueTokens(user, clientIp, deviceInfo);
    }

    @Override
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        User existingUser = userRepository.findByEmail(email).orElse(null);
        if (existingUser != null) {
            if (!Boolean.TRUE.equals(existingUser.getIsVerified())) {
                throw new ApiException(ErrorCode.PENDING_VERIFICATION,
                        "Email này chưa được xác thực. Bạn có muốn gửi mã mới không?");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }
        String username = blankToNull(request.getUsername());
        if (username != null && userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already registered");
        }
        checkProfileText(request.getUsername(), request.getFullName());
        requirePlausibleBirthDate(request.getDateOfBirth());
        Role learner = roleRepository.findByName("LEARNER")
                .orElseGet(() -> roleRepository.save(Role.builder().name("LEARNER").description("Learner").build()));
        User user = User.builder()
                .role(learner)
                .email(email)
                .username(username)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .loginType(LoginType.LOCAL.name())
                .fullName(blankToNull(request.getFullName()))
                .japaneseLevel(blankToNull(request.getJapaneseLevel()))
                .dateOfBirth(request.getDateOfBirth())
                .creditBalance(0)
                .isVerified(false)
                .build();
        userRepository.save(user);
        issueAndSendVerification(user, false);
        return userMapper.toResponse(user);
    }

    @Override
    public LoginResponse refresh(RefreshTokenRequest request) {
        String rawToken = request.getRefreshToken().trim();
        AuthToken stored = authTokenRepository.findByTokenHash(hash(rawToken))
                .filter(token -> REFRESH.equals(token.getTokenType()))
                .filter(token -> token.getRevokedAt() == null && token.getUsedAt() == null)
                .filter(token -> token.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> unauthorized("Invalid or expired refresh token"));
        assertUnlocked(stored.getUser());
        stored.setUsedAt(LocalDateTime.now());
        stored.setRevokedAt(LocalDateTime.now());
        return issueTokens(stored.getUser(), stored.getIpAddress(), stored.getDeviceInfo());
    }

    @Override
    public UserResponse getCurrentUser(Long userId) {
        return userMapper.toResponse(findUser(userId));
    }

    @Override
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findForProfileUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        String username = blankToNull(request.getUsername());
        if (username != null && !username.equals(user.getUsername()) && userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already registered");
        }
        checkProfileText(request.getUsername(), request.getFullName());
        String avatar = blankToNull(request.getAvatar());
        if (avatar != null && !avatar.equals(user.getAvatarUrl())) avatarUploads.assertApproved(userId, avatar);
        if (request.getUsername() != null) user.setUsername(username);
        if (request.getFullName() != null) user.setFullName(blankToNull(request.getFullName()));
        if (request.getPhoneNumber() != null) user.setPhoneNumber(blankToNull(request.getPhoneNumber()));
        if (request.getGender() != null) user.setGender(blankToNull(request.getGender()));
        if (request.getDateOfBirth() != null) setDateOfBirthOnce(user, request.getDateOfBirth());
        if (request.getJapaneseLevel() != null) user.setJapaneseLevel(blankToNull(request.getJapaneseLevel()));
        if (request.getAvatar() != null) user.setAvatarUrl(blankToNull(request.getAvatar()));
        return userMapper.toResponse(userRepository.save(user));
    }

    /**
     * Ngày sinh quyết định video Shadowing có nhãn độ tuổi nào được xem, nên người học chỉ đặt được một lần
     * (tài khoản cũ / Google chưa có); muốn đổi phải qua quản trị viên. Gửi lại đúng ngày đã lưu thì bỏ qua,
     * vì trang hồ sơ gửi lại mọi trường mỗi lần lưu.
     */
    private static void setDateOfBirthOnce(User user, LocalDate dateOfBirth) {
        if (dateOfBirth.equals(user.getDateOfBirth())) return;
        if (user.getDateOfBirth() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "Ngày sinh đã được lưu. Liên hệ quản trị viên nếu cần thay đổi.");
        }
        requirePlausibleBirthDate(dateOfBirth);
        user.setDateOfBirth(dateOfBirth);
    }

    private static void requirePlausibleBirthDate(LocalDate dateOfBirth) {
        if (!BirthDates.isPlausible(dateOfBirth, BirthDates.today())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, BirthDates.OUT_OF_RANGE);
        }
    }

    private void checkProfileText(String username, String fullName) {
        for (String text : new String[] { username, fullName }) {
            if (text == null || text.isBlank()) continue;
            var result = moderation.scanText(text);
            if (!result.isSafe()) throw new ApiException(result.isBlocked() ? ErrorCode.CONTENT_BLOCKED
                    : ErrorCode.CONTENT_PENDING_REVIEW, "Tên tài khoản hoặc tên hiển thị không phù hợp. Vui lòng chọn tên khác");
        }
    }

    @Override
    public LoginResponse verifyEmail(VerifyEmailRequest request) {
        AuthToken token = resolveVerificationToken(request);
        if (token.getUsedAt() != null || token.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification token has already been used");
        }
        if (!token.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification token has expired");
        }
        User user = token.getUser();
        assertUnlocked(user);
        if (Boolean.TRUE.equals(user.getIsVerified())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already verified");
        }
        user.setIsVerified(true);
        token.setUsedAt(LocalDateTime.now());
        authTokenRepository.save(token);
        userRepository.save(user);
        return issueTokens(user);
    }

    @Override
    public void resendVerification(ResendVerificationRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.getEmail()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (Boolean.TRUE.equals(user.getIsVerified())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already verified");
        }
        issueAndSendVerification(user, true);
    }

    @Override
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = findUser(userId);
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        if (passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different from the current password");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
        authTokenRepository.deleteByUser_IdAndTokenType(userId, REFRESH);
    }

    @Override
    public LoginResponse loginWithGoogle(GoogleLoginRequest request) {
        return loginWithGoogle(request, null, null);
    }

    @Override
    public LoginResponse loginWithGoogle(GoogleLoginRequest request, String clientIp, String deviceInfo) {
        GoogleTokenService.GoogleUserInfo googleUser = googleTokenService.verify(request.getIdToken());
        String email = normalizeEmail(googleUser.email());
        User user = userRepository.findByGoogleId(googleUser.googleId())
                .orElseGet(() -> userRepository.findForAuthentication(email).orElse(null));
        if (user == null) {
            String googleName = blankToNull(googleUser.fullName());
            if (googleName != null && !moderation.scanText(googleName).isSafe()) googleName = "Người học";
            Role learner = roleRepository.findByName("LEARNER")
                    .orElseGet(() -> roleRepository.save(Role.builder().name("LEARNER").description("Learner").build()));
            user = User.builder()
                    .role(learner)
                    .email(email)
                    .googleId(googleUser.googleId())
                    .loginType(LoginType.GOOGLE.name())
                    .fullName(googleName)
                    .avatarUrl(blankToNull(googleUser.avatar()))
                    .creditBalance(0)
                    .isVerified(true)
                    .build();
        } else {
            assertUnlocked(user);
            String linkedId = blankToNull(user.getGoogleId());
            if (linkedId != null && !linkedId.equals(googleUser.googleId())) {
                throw new ApiException(ErrorCode.CONFLICT, "Email này đã liên kết với tài khoản Google khác.");
            }
            // A verified local account with the same Google-verified email is linked on the spot. Both sides
            // prove ownership of the same mailbox, so the password and login type stay as they are.
            if (!Boolean.TRUE.equals(user.getIsVerified())) {
                // Google proves mailbox ownership; pre-registration does not prove the old local credential.
                user.setPasswordHash(null);
                user.setLoginType(LoginType.GOOGLE.name());
                for (String type : List.of(REFRESH, VERIFY_EMAIL, "RESET_PASSWORD")) {
                    authTokenRepository.deleteByUser_IdAndTokenType(user.getId(), type);
                }
            }
            user.setGoogleId(googleUser.googleId());
            if (blankToNull(user.getAvatarUrl()) == null) user.setAvatarUrl(blankToNull(googleUser.avatar()));
            user.setIsVerified(true);
        }
        boolean firstLogin = user.getLastLoginAt() == null;
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        notifications.greetDaily(user.getId(), user.getFullName(), firstLogin);
        return issueTokens(user, clientIp, deviceInfo);
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        passwordResets.issue(request.getEmail());
    }

    @Override
    public void resetPassword(ResetPasswordRequest request) {
        passwordResets.reset(request);
    }

    @Override
    public void verifyPasswordResetOtp(VerifyPasswordResetOtpRequest request) {
        passwordResets.verify(request.getEmail(), request.getOtp());
    }

    @Override
    public void logout(String token) {
        authTokenRepository.findByTokenHash(hash(token.trim())).ifPresent(stored -> {
            stored.setRevokedAt(LocalDateTime.now());
            authTokenRepository.save(stored);
        });
    }

    private LoginResponse issueTokens(User user) {
        return issueTokens(user, null, null);
    }

    private LoginResponse issueTokens(User user, String clientIp, String deviceInfo) {
        assertUnlocked(user);
        String normalizedIp = truncate(blankToNull(clientIp), 45);
        String normalizedDevice = truncate(blankToNull(deviceInfo), 255);
        notifyOnNewLogin(user, normalizedIp, normalizedDevice);
        String accessToken = JwtUtil.generateToken(user.getEmail(), authProperties.jwtSecret(), authProperties.accessTokenTtl());
        String refreshToken = randomToken();
        authTokenRepository.save(AuthToken.builder()
                .user(user)
                .tokenType(REFRESH)
                .tokenHash(hash(refreshToken))
                .ipAddress(normalizedIp)
                .deviceInfo(normalizedDevice)
                .expiresAt(LocalDateTime.now().plusSeconds(authProperties.refreshTokenTtl()))
                .build());
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(authProperties.accessTokenTtl())
                .user(userMapper.toResponse(user))
                .build();
    }

    private void notifyOnNewLogin(User user, String clientIp, String deviceInfo) {
        if (clientIp == null && deviceInfo == null) return;
        List<AuthToken> previous = authTokenRepository
                .findTop20ByUser_IdAndTokenTypeOrderByCreatedAtDesc(user.getId(), REFRESH);
        List<AuthToken> comparable = previous.stream()
                .filter(token -> token.getIpAddress() != null || token.getDeviceInfo() != null)
                .toList();
        if (comparable.isEmpty()) return;
        boolean knownIp = clientIp == null || comparable.stream().anyMatch(token -> clientIp.equals(token.getIpAddress()));
        boolean knownDevice = deviceInfo == null || comparable.stream().anyMatch(token -> deviceInfo.equals(token.getDeviceInfo()));
        if (knownIp && knownDevice) return;

        String deviceLabel = describeDevice(deviceInfo);
        String eventKey = "NEW_LOGIN_" + hash((clientIp == null ? "" : clientIp)
                + "|" + (deviceInfo == null ? "" : deviceInfo)).substring(0, 20);
        notifications.notifyUser(user.getId(), "SECURITY", "Phát hiện đăng nhập mới",
                "Tài khoản vừa đăng nhập trên " + deviceLabel
                        + ". Nếu không phải bạn, hãy đổi mật khẩu ngay.",
                "/profile/security/login-alerts", eventKey);
    }

    private static String describeDevice(String userAgent) {
        if (userAgent == null) return "thiết bị mới";
        String lower = userAgent.toLowerCase(Locale.ROOT);
        String platform = lower.contains("android") ? "Android"
                : lower.contains("iphone") || lower.contains("ipad") ? "iPhone/iPad"
                : lower.contains("windows") ? "Windows"
                : lower.contains("mac os") || lower.contains("macintosh") ? "macOS"
                : lower.contains("linux") ? "Linux" : "thiết bị mới";
        String browser = lower.contains("edg/") ? "Edge"
                : lower.contains("firefox/") ? "Firefox"
                : lower.contains("chrome/") ? "Chrome"
                : lower.contains("safari/") ? "Safari" : null;
        return browser == null ? platform : browser + " trên " + platform;
    }

    private static String truncate(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }

    private AuthToken resolveVerificationToken(VerifyEmailRequest request) {
        String rawToken = blankToNull(request.getToken());
        if (rawToken != null) {
            return authTokenRepository.findByTokenTypeAndTokenHash(VERIFY_EMAIL, hash(rawToken))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification token"));
        }
        String email = blankToNull(request.getEmail());
        String otp = blankToNull(request.getOtp());
        if (email == null || otp == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide either a verification token or email and OTP");
        }
        return authTokenRepository.findByTokenTypeAndOtpHashAndUser_EmailAndUsedAtIsNullAndRevokedAtIsNull(
                VERIFY_EMAIL, hash(otp), normalizeEmail(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OTP code"));
    }

    private void issueAndSendVerification(User user, boolean enforceResendLimit) {
        LocalDateTime now = LocalDateTime.now();
        if (enforceResendLimit) {
            AuthToken latestVerification = authTokenRepository
                    .findFirstByUser_EmailAndTokenTypeOrderByCreatedAtDesc(user.getEmail(), VERIFY_EMAIL)
                    .orElse(null);
            if (latestVerification != null
                    && latestVerification.getCreatedAt().isAfter(now.minusMinutes(VERIFICATION_RESEND_COOLDOWN_MINUTES))) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                        "Bạn chỉ có thể yêu cầu mã OTP mới sau 3 phút.");
            }

            long sentInLastHour = authTokenRepository.countByUser_IdAndTokenTypeAndCreatedAtAfter(
                    user.getId(), VERIFY_EMAIL, now.minusHours(1));
            if (sentInLastHour >= VERIFICATION_RESEND_LIMIT_PER_HOUR + 1L) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                        "Bạn đã yêu cầu gửi lại mã quá nhiều lần. Vui lòng thử lại sau một giờ.");
            }
        }

        List<AuthToken> activeTokens = authTokenRepository
                .findByUser_IdAndTokenTypeAndUsedAtIsNullAndRevokedAtIsNull(user.getId(), VERIFY_EMAIL);
        activeTokens.forEach(token -> token.setRevokedAt(now));
        authTokenRepository.saveAll(activeTokens);
        String token = randomToken();
        String otp = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        authTokenRepository.save(AuthToken.builder()
                .user(user)
                .tokenType(VERIFY_EMAIL)
                .tokenHash(hash(token))
                .otpHash(hash(otp))
                .expiresAt(LocalDateTime.now().plusSeconds(verificationTokenTtl))
                .build());
        emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), otp);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static void assertUnlocked(User user) {
        if (user.getLockedAt() != null) throw new ApiException(ErrorCode.ACCOUNT_LOCKED);
    }

    private static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash token", exception);
        }
    }
}
