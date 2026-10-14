package com.jlas.auth.service;

import com.jlas.auth.dto.ResetPasswordRequest;
import com.jlas.auth.entity.AuthToken;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.AuthTokenRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits wrong-OTP accounting before returning an API error; serializes consumption per user. */
@Service
public class PasswordResetService {
    private static final String RESET = "RESET_PASSWORD";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;
    private final UserRepository users;
    private final AuthTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final EmailService email;
    private final TransactionTemplate transactions;

    public PasswordResetService(UserRepository users, AuthTokenRepository tokens, PasswordEncoder passwords,
                                EmailService email, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        this.email = email;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void issue(String address) {
        transactions.executeWithoutResult(status -> users.findForAuthentication(normalize(address)).ifPresent(user -> {
            LocalDateTime now = LocalDateTime.now();
            AuthToken latest = tokens.findFirstByUser_EmailAndTokenTypeOrderByCreatedAtDesc(user.getEmail(), RESET).orElse(null);
            if (latest != null && latest.getCreatedAt().isAfter(now.minusMinutes(1))) return;
            if (tokens.countByUser_IdAndTokenTypeAndCreatedAtAfter(user.getId(), RESET, now.minusHours(1)) >= 3) return;
            for (AuthToken active : tokens.findByUser_IdAndTokenTypeAndUsedAtIsNullAndRevokedAtIsNull(user.getId(), RESET)) {
                active.setRevokedAt(now);
            }
            String otp = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            tokens.save(AuthToken.builder().user(user).tokenType(RESET).tokenHash(hash(HexFormat.of().formatHex(random)))
                    .otpHash(hash(otp)).expiresAt(now.plusMinutes(15)).build());
            email.sendPasswordResetOtp(user.getEmail(), user.getFullName(), otp);
        }));
    }

    public void verify(String address, String otp) {
        ApiException error = transactions.execute(status -> check(address, otp).error());
        if (error != null) throw error;
    }

    public void reset(ResetPasswordRequest request) {
        ApiException error = transactions.execute(status -> {
            Check check = check(request.getEmail(), request.getOtp());
            if (check.error() != null) return check.error();
            AuthToken token = check.token();
            User user = token.getUser();
            if (user.getPasswordHash() != null && passwords.matches(request.getPassword(), user.getPasswordHash())) {
                return new ApiException(ErrorCode.BAD_REQUEST, "Mật khẩu mới phải khác mật khẩu hiện tại.");
            }
            user.setPasswordHash(passwords.encode(request.getPassword()));
            token.setUsedAt(LocalDateTime.now());
            users.save(user);
            tokens.save(token);
            tokens.deleteByUser_IdAndTokenType(user.getId(), "REFRESH");
            return null;
        });
        if (error != null) throw error;
    }

    private Check check(String address, String otp) {
        User user = users.findForAuthentication(normalize(address)).orElse(null);
        AuthToken token = user == null ? null : tokens
                .findFirstByUser_EmailAndTokenTypeOrderByCreatedAtDesc(user.getEmail(), RESET).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (token == null || token.getUsedAt() != null || token.getRevokedAt() != null
                || !token.getExpiresAt().isAfter(now) || token.getAttemptCount() >= MAX_ATTEMPTS) {
            return invalid();
        }
        String candidate = hash(otp);
        if (token.getOtpHash() == null || !MessageDigest.isEqual(token.getOtpHash().getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII))) {
            token.setAttemptCount(token.getAttemptCount() + 1);
            if (token.getAttemptCount() >= MAX_ATTEMPTS) token.setRevokedAt(now);
            tokens.save(token);
            return invalid();
        }
        return new Check(token, null);
    }

    private static Check invalid() {
        return new Check(null, new ApiException(ErrorCode.BAD_REQUEST,
                "Mã OTP không đúng, đã hết hạn hoặc bị khóa. Vui lòng yêu cầu mã mới."));
    }

    private static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Check(AuthToken token, ApiException error) { }
}
