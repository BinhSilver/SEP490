package com.jlas.kaiwa.service.quota;

import com.jlas.auth.entity.PremiumPlan;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.kaiwa.config.AiCallQuotaProperties;
import com.jlas.kaiwa.repository.AiCallUsageRepository;
import com.jlas.moderation.entity.AiUsageDaily;
import com.jlas.moderation.entity.AiUsageDaily.AiUsageDailyId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Hạn mức AI Call theo phút (JLAS-W0199). Con số lấy từ {@link AiCallQuotaProperties}.
 * <ul>
 *   <li>Tài khoản thường: hạn mức ngày + tháng.</li>
 *   <li>Premium còn hạn (gói khác FREE, premium_expired_at sau hiện tại): hạn mức ngày cao hơn.</li>
 *   <li>ADMIN, SUPER_ADMIN: không giới hạn.</li>
 * </ul>
 * Phút được giữ chỗ trước khi gọi AI ({@link #tryReserve}) và hoàn lại nếu lượt đó lỗi ({@link #release}).
 */
@Service
@RequiredArgsConstructor
public class AiCallQuotaService {

    private static final String FREE_PLAN = "FREE";

    private final AiCallUsageRepository usageRepository;
    private final UserRepository userRepository;
    private final AiCallQuotaProperties properties;

    public double minutesPerTurn() {
        return properties.minutesPerTurn();
    }

    public int creditPerTurn() {
        return properties.creditPerTurn();
    }

    public boolean isUnlimited(UserPrincipal user) {
        return user.getAuthorities().stream().anyMatch(authority ->
                "ROLE_ADMIN".equals(authority.getAuthority()) || "ROLE_SUPER_ADMIN".equals(authority.getAuthority()));
    }

    /**
     * Giữ chỗ {@code minutes} phút trong hạn mức hôm nay. Trả về ngày đã cộng (để hoàn đúng ngày),
     * hoặc rỗng nếu vượt hạn mức ngày/tháng. Khoá dòng ai_usage_daily để hai lượt song song không vượt trần.
     */
    @Transactional
    public Optional<LocalDate> tryReserve(Long userId, double minutes) {
        // An existing per-user row serializes daily creation and monthly accounting.
        // Locking a missing daily row can otherwise deadlock on MySQL gap locks.
        userRepository.findForCreditUpdate(userId).orElseThrow();
        LocalDate today = LocalDate.now();
        AiUsageDaily usage = usageRepository.lockByUserIdAndDate(userId, today)
                .orElseGet(() -> AiUsageDaily.builder()
                        .id(new AiUsageDailyId(userId, today))
                        .user(userRepository.getReferenceById(userId))
                        .build());
        boolean premium = isPremium(userId);
        double used = usage.getMinutesUsed().doubleValue();
        if (used + minutes > dailyLimit(premium) || monthlyUsed(userId, today) + minutes > monthlyLimit(premium)) {
            return Optional.empty();
        }
        usage.setMinutesUsed(usage.getMinutesUsed().add(minutes(minutes)));
        usageRepository.save(usage);
        return Optional.of(today);
    }

    /** Hoàn lại phút đã giữ chỗ cho một lượt lỗi. */
    @Transactional
    public void release(Long userId, LocalDate date, BigDecimal minutes) {
        if (date == null || minutes == null || minutes.signum() <= 0) {
            return;
        }
        userRepository.findForCreditUpdate(userId).orElseThrow();
        usageRepository.lockByUserIdAndDate(userId, date).ifPresent(usage -> {
            usage.setMinutesUsed(usage.getMinutesUsed().subtract(minutes).max(BigDecimal.ZERO));
            usageRepository.save(usage);
        });
    }

    @Transactional(readOnly = true)
    public UsageSnapshot snapshot(UserPrincipal user) {
        long credits = userRepository.findById(user.userId()).map(User::getCreditBalance).map(Integer::longValue).orElse(0L);
        if (isUnlimited(user)) {
            return new UsageSnapshot(0, 0, Double.MAX_VALUE, Double.MAX_VALUE, true, credits, properties.creditPerTurn());
        }
        LocalDate today = LocalDate.now();
        double daily = usageRepository.findByUserIdAndDate(user.userId(), today)
                .map(usage -> usage.getMinutesUsed().doubleValue()).orElse(0.0);
        boolean premium = isPremium(user.userId());
        double monthlyLimit = monthlyLimit(premium);
        return new UsageSnapshot(daily, monthlyUsed(user.userId(), today), dailyLimit(premium), monthlyLimit,
                monthlyLimit == Double.MAX_VALUE, credits, properties.creditPerTurn());
    }

    public static BigDecimal minutes(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    private double dailyLimit(boolean premium) {
        return premium ? properties.premiumDailyMinutes() : properties.freeDailyMinutes();
    }

    private double monthlyLimit(boolean premium) {
        if (!premium) {
            return properties.freeMonthlyMinutes();
        }
        return properties.premiumMonthlyMinutes() <= 0 ? Double.MAX_VALUE : properties.premiumMonthlyMinutes();
    }

    private double monthlyUsed(Long userId, LocalDate today) {
        BigDecimal value = usageRepository.sumMinutesBetween(
                userId, YearMonth.from(today).atDay(1), YearMonth.from(today).atEndOfMonth());
        return value == null ? 0.0 : value.doubleValue();
    }

    private boolean isPremium(Long userId) {
        return userRepository.findById(userId).map(user -> {
            PremiumPlan plan = user.getPremiumPlan();
            return plan != null && !FREE_PLAN.equalsIgnoreCase(plan.getName())
                    && user.getPremiumExpiredAt() != null
                    && user.getPremiumExpiredAt().isAfter(LocalDateTime.now());
        }).orElse(false);
    }

    /**
     * Tên trường khớp với modal hết hạn mức trong conversation.js
     * (dailyMinutes, dailyLimitMin, monthlyMinutes, monthlyLimitMin, creditBalance, creditPerTurn).
     */
    public record UsageSnapshot(double dailyMinutes, double monthlyMinutes,
                                double dailyLimitMin, double monthlyLimitMin, boolean unlimited,
                                long creditBalance, int creditPerTurn) {
    }

    /** Hết hạn mức: HTTP 402 kèm số liệu để giao diện hiện modal (chờ ngày mai / dùng CP / nâng cấp). */
    public static class QuotaExceededException extends RuntimeException {
        private final UsageSnapshot snapshot;

        public QuotaExceededException(String message, UsageSnapshot snapshot) {
            super(message);
            this.snapshot = snapshot;
        }

        public UsageSnapshot getSnapshot() {
            return snapshot;
        }
    }
}
