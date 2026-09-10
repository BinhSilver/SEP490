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
import com.jlas.auth.util.JwtUtil;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
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
    private static final String RESET_PASSWORD = "RESET_PASSWORD";
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

    @Value("${jlas.auth.verification-token-ttl:900}")
    private long verificationTokenTtl;

    @Override
    public LoginResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> unauthorized("Invalid email or password"));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw unauthorized("Invalid email or password");
        }
        if (!Boolean.TRUE.equals(user.getIsVerified())) {
            throw new ApiException(ErrorCode.EMAIL_UNVERIFIED,
                    "Email is not verified. Please verify your email before logging in.");
        }
        user.setLastLoginAt(LocalDateTime.now());
        return issueTokens(user);
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
        stored.setUsedAt(LocalDateTime.now());
        stored.setRevokedAt(LocalDateTime.now());
        return issueTokens(stored.getUser());
    }

    @Override
    public UserResponse getCurrentUser(Long userId) {
        return userMapper.toResponse(findUser(userId));
    }

    @Override
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = findUser(userId);
        String username = blankToNull(request.getUsername());
        if (username != null && !username.equals(user.getUsername()) && userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is already registered");
        }
        if (request.getUsername() != null) user.setUsername(username);
        if (request.getFullName() != null) user.setFullName(blankToNull(request.getFullName()));
        if (request.getPhoneNumber() != null) user.setPhoneNumber(blankToNull(request.getPhoneNumber()));
        if (request.getGender() != null) user.setGender(blankToNull(request.getGender()));
        if (request.getJapaneseLevel() != null) user.setJapaneseLevel(blankToNull(request.getJapaneseLevel()));
        if (request.getAvatar() != null) user.setAvatarUrl(blankToNull(request.getAvatar()));
        return userMapper.toResponse(userRepository.save(user));
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
        GoogleTokenService.GoogleUserInfo googleUser = googleTokenService.verify(request.getIdToken());
        String email = normalizeEmail(googleUser.email());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            Role learner = roleRepository.findByName("LEARNER")
                    .orElseGet(() -> roleRepository.save(Role.builder().name("LEARNER").description("Learner").build()));
            user = User.builder()
                    .role(learner)
                    .email(email)
                    .googleId(googleUser.googleId())
                    .loginType(LoginType.GOOGLE.name())
                    .fullName(blankToNull(googleUser.fullName()))
                    .avatarUrl(blankToNull(googleUser.avatar()))
                    .creditBalance(0)
                    .isVerified(true)
                    .build();
        } else {
            if (blankToNull(user.getGoogleId()) == null) user.setGoogleId(googleUser.googleId());
            if (blankToNull(user.getAvatarUrl()) == null) user.setAvatarUrl(blankToNull(googleUser.avatar()));
            user.setIsVerified(true);
        }
        userRepository.save(user);
        return issueTokens(user);
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(normalizeEmail(request.getEmail())).ifPresent(user -> {
            authTokenRepository.deleteByUser_IdAndTokenType(user.getId(), RESET_PASSWORD);
            String token = randomToken();
            authTokenRepository.save(AuthToken.builder()
                    .user(user)
                    .tokenType(RESET_PASSWORD)
                    .tokenHash(hash(token))
                    .expiresAt(LocalDateTime.now().plusMinutes(30))
                    .build());
            emailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(), token);
        });
    }

    @Override
    public void logout(String token) {
        authTokenRepository.findByTokenHash(hash(token.trim())).ifPresent(stored -> {
            stored.setRevokedAt(LocalDateTime.now());
            authTokenRepository.save(stored);
        });
    }

    private LoginResponse issueTokens(User user) {
        String accessToken = JwtUtil.generateToken(user.getEmail(), authProperties.jwtSecret(), authProperties.accessTokenTtl());
        String refreshToken = randomToken();
        authTokenRepository.save(AuthToken.builder()
                .user(user)
                .tokenType(REFRESH)
                .tokenHash(hash(refreshToken))
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
