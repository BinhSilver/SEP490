package com.jlas.common.usage;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.auth.service.PremiumAccess;
import com.jlas.dictionary.service.QuickLookupAllowance;
import com.jlas.kaiwa.service.quota.AiCallQuotaService;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.shadowing.service.PronunciationQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mức sử dụng của một tài khoản, gom từ các hạn mức của kế hoạch Pro/CP để hiện trong trang hồ sơ: AI Call,
 * chấm phát âm và tra từ nhanh.
 */
@Service
@RequiredArgsConstructor
public class UsageOverviewService {
    private final PremiumAccess premium;
    private final AiCallQuotaService aiCall;
    private final PronunciationQuotaService pronunciation;
    private final QuickLookupAllowance quickLookups;
    private final UserCreditService credits;

    /**
     * {@code monthlyLimitMinutes} null = không giới hạn tháng; {@code turnsLeft} null = không giới hạn (admin).
     * {@code minutesPerTurn}: một lượt hội thoại tính bao nhiêu phút hạn mức.
     */
    public record AiCall(double minutesToday, double dailyLimitMinutes, double minutesThisMonth,
                         Double monthlyLimitMinutes, Integer turnsLeft, double minutesPerTurn, int creditPerTurn,
                         boolean unlimited) {
    }

    public record Pronunciation(int usedToday, int dailyLimit, int creditPrice) {
    }

    public record QuickLookup(boolean unlimited, int remaining, int packLookups, int packPrice) {
    }

    public record UsageOverview(boolean pro, long creditBalance, AiCall aiCall, Pronunciation pronunciation,
                                QuickLookup quickLookup) {
    }

    @Transactional(readOnly = true)
    public UsageOverview overview(UserPrincipal user) {
        Long userId = user.userId();
        AiCallQuotaService.UsageSnapshot calls = aiCall.snapshot(user);
        boolean callsUnlimited = calls.dailyLimitMin() == Double.MAX_VALUE;
        AiCall aiCallUsage = new AiCall(calls.dailyMinutes(), callsUnlimited ? 0 : calls.dailyLimitMin(),
                calls.monthlyMinutes(), calls.monthlyLimitMin() == Double.MAX_VALUE ? null : calls.monthlyLimitMin(),
                aiCall.turnsLeft(user), aiCall.minutesPerTurn(), aiCall.creditPerTurn(), callsUnlimited);
        PronunciationQuotaService.Status checks = pronunciation.status(userId);
        QuickLookupAllowance.Status lookups = quickLookups.status(userId);
        return new UsageOverview(premium.isActive(userId), credits.balance(userId), aiCallUsage,
                new Pronunciation(checks.usedToday(), checks.dailyLimit(), checks.creditPrice()),
                new QuickLookup(lookups.unlimited(), lookups.remaining(), lookups.packLookups(), lookups.packPrice()));
    }
}
