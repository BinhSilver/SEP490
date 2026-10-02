package com.jlas.auth.service;

import com.jlas.auth.dto.ConfirmEmailChangeRequest;
import com.jlas.auth.dto.GoogleLoginRequest;
import com.jlas.auth.dto.LoginHistoryItem;
import com.jlas.auth.dto.RequestEmailChangeRequest;
import com.jlas.auth.dto.UserResponse;
import com.jlas.auth.entity.User;
import com.jlas.auth.mapper.UserMapper;
import com.jlas.auth.repository.AuthTokenRepository;
import com.jlas.auth.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AccountSecurityService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;
    private static final long OTP_TTL_MINUTES = 15;
    private static final String REFRESH = "REFRESH";

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuthTokenRepository authTokens;
    private final PasswordEncoder passwordEncoder;
    private final GoogleTokenService googleTokens;
    private final EmailService emailService;
    private final UserMapper userMapper;

    @Transactional
    public void requestEmailChange(Long userId, RequestEmailChangeRequest request) {
        User user = findUser(userId);
        String newEmail = normalizeEmail(request.getNewEmail());
        if (newEmail.equalsIgnoreCase(user.getEmail())) {
            throw badRequest("Email mới phải khác email hiện tại.");
        }
        if (users.existsByEmail(newEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email mới đã được sử dụng.");
        }
        if (user.getPasswordHash() != null && !user.getPasswordHash().isBlank()) {
            if (request.getCurrentPassword() == null
                    || !passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
                throw badRequest("Mật khẩu hiện tại không đúng.");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        Integer recentRequests = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_change_requests WHERE user_id = ? AND created_at > ?",
                Integer.class, userId, now.minusHours(1));
        if (recentRequests != null && recentRequests >= 5) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã yêu cầu quá nhiều mã OTP. Vui lòng thử lại sau.");
        }
        Integer cooldownRequests = jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_change_requests WHERE user_id = ? AND created_at > ?",
                Integer.class, userId, now.minusMinutes(1));
        if (cooldownRequests != null && cooldownRequests > 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Vui lòng chờ một phút trước khi yêu cầu mã mới.");
        }
        jdbc.update("UPDATE email_change_requests SET revoked_at = ? "
                + "WHERE user_id = ? AND confirmed_at IS NULL AND revoked_at IS NULL", now, userId);
        String oldOtp = otp();
        String newOtp = otp();
        jdbc.update("INSERT INTO email_change_requests "
                        + "(user_id, new_email, old_otp_hash, new_otp_hash, expires_at) VALUES (?, ?, ?, ?, ?)",
                userId, newEmail, hash(oldOtp), hash(newOtp), now.plusMinutes(OTP_TTL_MINUTES));

        emailService.sendEmailChangeOtp(user.getEmail(), user.getFullName(), oldOtp, false);
        emailService.sendEmailChangeOtp(newEmail, user.getFullName(), newOtp, true);
    }

    @Transactional
    public void confirmEmailChange(Long userId, ConfirmEmailChangeRequest request) {
        List<EmailChangeChallenge> challenges = jdbc.query(
                "SELECT id, new_email, old_otp_hash, new_otp_hash, attempt_count, expires_at "
                        + "FROM email_change_requests WHERE user_id = ? AND confirmed_at IS NULL "
                        + "AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1",
                (result, row) -> mapChallenge(result), userId);
        if (challenges.isEmpty()) throw badRequest("Không tìm thấy yêu cầu đổi email đang chờ xác nhận.");

        EmailChangeChallenge challenge = challenges.getFirst();
        if (!challenge.expiresAt().isAfter(LocalDateTime.now())) {
            jdbc.update("UPDATE email_change_requests SET revoked_at = ? WHERE id = ?", LocalDateTime.now(), challenge.id());
            throw badRequest("Mã OTP đã hết hạn. Vui lòng gửi yêu cầu mới.");
        }
        if (challenge.attemptCount() >= MAX_ATTEMPTS) {
            throw badRequest("Yêu cầu đã bị khóa do nhập sai quá nhiều lần.");
        }

        boolean oldMatches = constantTimeEquals(challenge.oldOtpHash(), hash(request.getOldEmailOtp()));
        boolean newMatches = constantTimeEquals(challenge.newOtpHash(), hash(request.getNewEmailOtp()));
        if (!oldMatches || !newMatches) {
            jdbc.update("UPDATE email_change_requests SET attempt_count = attempt_count + 1 WHERE id = ?", challenge.id());
            throw badRequest("Một trong hai mã OTP không đúng.");
        }
        if (users.existsByEmail(challenge.newEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email mới đã được sử dụng.");
        }

        User user = findUser(userId);
        String oldEmail = user.getEmail();
        user.setEmail(challenge.newEmail());
        user.setIsVerified(true);
        users.save(user);
        jdbc.update("UPDATE email_change_requests SET confirmed_at = ? WHERE id = ?", LocalDateTime.now(), challenge.id());
        authTokens.deleteByUser_IdAndTokenType(userId, REFRESH);
        emailService.sendEmailChangedNotice(oldEmail, user.getFullName(), challenge.newEmail());
        emailService.sendEmailChangedNotice(challenge.newEmail(), user.getFullName(), challenge.newEmail());
    }

    @Transactional
    public UserResponse linkGoogle(Long userId, GoogleLoginRequest request) {
        User user = findUser(userId);
        GoogleTokenService.GoogleUserInfo google = googleTokens.verify(request.getIdToken());
        if (!normalizeEmail(google.email()).equalsIgnoreCase(user.getEmail())) {
            throw badRequest("Hãy chọn tài khoản Google có cùng email với hồ sơ hiện tại.");
        }
        users.findByGoogleId(google.googleId())
                .filter(owner -> !owner.getId().equals(userId))
                .ifPresent(owner -> { throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Tài khoản Google này đã liên kết với hồ sơ khác."); });
        user.setGoogleId(google.googleId());
        if ((user.getFullName() == null || user.getFullName().isBlank()) && google.fullName() != null) {
            user.setFullName(google.fullName().trim());
        }
        if ((user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) && google.avatar() != null) {
            user.setAvatarUrl(google.avatar().trim());
        }
        return userMapper.toResponse(users.save(user));
    }

    @Transactional
    public UserResponse unlinkGoogle(Long userId) {
        User user = findUser(userId);
        if (user.getGoogleId() == null || user.getGoogleId().isBlank()) return userMapper.toResponse(user);
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw badRequest("Hãy tạo mật khẩu trước khi hủy liên kết Google để tránh mất quyền truy cập.");
        }
        user.setGoogleId(null);
        user.setLoginType("LOCAL");
        return userMapper.toResponse(users.save(user));
    }

    @Transactional(readOnly = true)
    public List<LoginHistoryItem> loginHistory(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return authTokens.findTop20ByUser_IdAndTokenTypeOrderByCreatedAtDesc(userId, REFRESH).stream()
                .map(token -> new LoginHistoryItem(
                        describeDevice(token.getDeviceInfo()),
                        visibleIpAddress(token.getIpAddress()),
                        token.getCreatedAt(),
                        token.getRevokedAt() == null && token.getExpiresAt().isAfter(now)))
                .toList();
    }

    /** Do not surface the server's own loopback address as if it were a user location. */
    private static String visibleIpAddress(String value) {
        if (value == null || value.isBlank()) return null;
        String ip = value.trim();
        return switch (ip) {
            case "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost" -> null;
            default -> ip;
        };
    }

    private User findUser(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng."));
    }

    private static EmailChangeChallenge mapChallenge(ResultSet result) throws SQLException {
        return new EmailChangeChallenge(result.getLong("id"), result.getString("new_email"),
                result.getString("old_otp_hash"), result.getString("new_otp_hash"),
                result.getInt("attempt_count"), result.getTimestamp("expires_at").toLocalDateTime());
    }

    private static String otp() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    private static String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String describeDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Thiết bị chưa xác định";
        String lower = userAgent.toLowerCase(Locale.ROOT);
        String platform = lower.contains("android") ? "Android"
                : lower.contains("iphone") || lower.contains("ipad") ? "iPhone/iPad"
                : lower.contains("windows") ? "Windows"
                : lower.contains("mac os") || lower.contains("macintosh") ? "macOS"
                : lower.contains("linux") ? "Linux" : "Thiết bị chưa xác định";
        String browser = lower.contains("edg/") ? "Edge"
                : lower.contains("firefox/") ? "Firefox"
                : lower.contains("chrome/") ? "Chrome"
                : lower.contains("safari/") ? "Safari" : null;
        return browser == null ? platform : browser + " trên " + platform;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash OTP", exception);
        }
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII), second.getBytes(StandardCharsets.US_ASCII));
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record EmailChangeChallenge(long id, String newEmail, String oldOtpHash,
                                        String newOtpHash, int attemptCount, LocalDateTime expiresAt) { }
}
