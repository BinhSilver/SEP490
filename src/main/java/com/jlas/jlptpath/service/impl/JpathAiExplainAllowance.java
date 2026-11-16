package com.jlas.jlptpath.service.impl;

import com.jlas.auth.service.PremiumAccess;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.security.ContentReviewAccess;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] F36: Hạn mức và ví lượt cho tính năng AI giải thích / hỏi đáp câu hỏi trong Lộ trình học.
 * <ul>
 *   <li>Tài khoản thường: tặng {@value #TRIAL_TURNS} lượt dùng thử miễn phí đầu tiên (1 lần duy nhất); hết lượt thử
 *       mua thêm {@value #PACK_TURNS} lượt với giá {@value #PACK_PRICE} CP.</li>
 *   <li>Tài khoản Pro (kể cả Pro tặng): miễn phí {@value #PRO_DAILY_LIMIT} lượt/ngày; vượt trần dùng lượt mua bằng CP.</li>
 *   <li>Xem lại cùng câu hỏi + đáp án đã giải thích trong ngày không tốn thêm lượt.</li>
 *   <li>Lỗi AI / mạng gọi {@link #release(Charge)} hoàn trả ngay lượt vừa giữ.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class JpathAiExplainAllowance {
    public static final int TRIAL_TURNS = 1;
    public static final int PRO_DAILY_LIMIT = 30;
    public static final int PACK_TURNS = 1;
    public static final int PACK_PRICE = 3;

    private final JdbcTemplate jdbc;
    private final PremiumAccess premium;
    private final UserCreditService credits;

    public enum ChargeKind {
        REVIEWER,
        REPEAT_TODAY,
        PRO_DAILY,
        TRIAL,
        WALLET
    }

    public record Charge(Long userId, ChargeKind kind, LocalDate usageDate) {
    }

    public record Status(
            boolean unlimited,
            boolean pro,
            int proUsedToday,
            int proDailyLimit,
            boolean trialAvailable,
            int walletRemaining,
            int remaining,
            long creditBalance,
            int packTurns,
            int packPrice) {
    }

    private record WalletRow(int remaining, boolean trialUsed) {
    }

    @Transactional(readOnly = true)
    public Status status(Long userId) {
        requireUser(userId);
        boolean reviewer = ContentReviewAccess.allowed(userId);
        boolean pro = premium.isActive(userId);
        LocalDate today = LocalDate.now();
        int proUsed = pro ? proUsed(userId, today) : 0;
        WalletRow wallet = readWallet(userId);
        boolean trialAvail = !reviewer && !pro && wallet == null;
        int walletRem = wallet == null ? 0 : wallet.remaining();
        int totalRemaining;
        if (reviewer) {
            totalRemaining = 999;
        } else if (pro) {
            totalRemaining = Math.max(0, PRO_DAILY_LIMIT - proUsed) + walletRem;
        } else {
            totalRemaining = walletRem + (trialAvail ? TRIAL_TURNS : 0);
        }
        return new Status(reviewer, pro, proUsed, PRO_DAILY_LIMIT, trialAvail, walletRem,
                totalRemaining, credits.balance(userId), PACK_TURNS, PACK_PRICE);
    }

    /**
     * Giữ 1 lượt trước khi gọi AI. Nếu {@code seenCacheKey} khác null và người học đã mở câu này hôm nay thì không tốn lượt.
     * Ném {@link ErrorCode#JPATH_AI_EXPLAIN_EXHAUSTED} khi hết lượt để trang hiện hộp mua bằng CP.
     */
    @Transactional
    public Charge reserve(Long userId, String seenCacheKey) {
        requireUser(userId);
        if (ContentReviewAccess.allowed(userId)) {
            return new Charge(userId, ChargeKind.REVIEWER, null);
        }
        LocalDate today = LocalDate.now();
        if (seenCacheKey != null && !seenCacheKey.isBlank() && seenToday(userId, today, seenCacheKey)) {
            return new Charge(userId, ChargeKind.REPEAT_TODAY, today);
        }
        boolean pro = premium.isActive(userId);
        if (pro) {
            jdbc.update("INSERT IGNORE INTO jpath_ai_explain_daily (user_id, usage_date, pro_used) VALUES (?, ?, 0)",
                    userId, today);
            int taken = jdbc.update("UPDATE jpath_ai_explain_daily SET pro_used = pro_used + 1 "
                    + "WHERE user_id = ? AND usage_date = ? AND pro_used < ?", userId, today, PRO_DAILY_LIMIT);
            if (taken > 0) {
                return new Charge(userId, ChargeKind.PRO_DAILY, today);
            }
        }
        boolean grantedTrial = ensureWallet(userId, !pro);
        int used = jdbc.update("UPDATE jpath_ai_explain_wallet SET remaining = remaining - 1, "
                + "updated_at = CURRENT_TIMESTAMP(6) WHERE user_id = ? AND remaining > 0", userId);
        if (used == 0) {
            throw new ApiException(ErrorCode.JPATH_AI_EXPLAIN_EXHAUSTED);
        }
        return new Charge(userId, grantedTrial ? ChargeKind.TRIAL : ChargeKind.WALLET, today);
    }

    /** Đánh dấu đã giải thích câu này hôm nay để mở lại trong ngày không tốn lượt. */
    @Transactional
    public void commitSeen(Long userId, String seenCacheKey) {
        if (userId == null || seenCacheKey == null || seenCacheKey.isBlank()) {
            return;
        }
        jdbc.update("INSERT IGNORE INTO jpath_ai_explain_seen (user_id, usage_date, cache_key) VALUES (?, ?, ?)",
                userId, LocalDate.now(), seenCacheKey);
    }

    /** Hoàn trả ngay lượt vừa giữ khi lời gọi AI gặp lỗi. */
    @Transactional
    public void release(Charge charge) {
        if (charge == null || charge.userId() == null) {
            return;
        }
        switch (charge.kind()) {
            case PRO_DAILY -> jdbc.update("UPDATE jpath_ai_explain_daily SET pro_used = pro_used - 1 "
                    + "WHERE user_id = ? AND usage_date = ? AND pro_used > 0", charge.userId(), charge.usageDate());
            case TRIAL, WALLET -> jdbc.update("UPDATE jpath_ai_explain_wallet SET remaining = remaining + 1, "
                    + "updated_at = CURRENT_TIMESTAMP(6) WHERE user_id = ?", charge.userId());
            default -> {
            }
        }
    }

    /** Dùng {@value #PACK_PRICE} CP mua thêm {@value #PACK_TURNS} lượt vào ví. */
    @Transactional
    public Status buyPack(Long userId) {
        requireUser(userId);
        boolean pro = premium.isActive(userId);
        ensureWallet(userId, !pro);
        credits.spend(userId, PACK_PRICE, CreditReason.LEARNING_AI_EXPLAIN, null, null,
                "Mua " + PACK_TURNS + " lượt AI giải thích câu hỏi");
        jdbc.update("UPDATE jpath_ai_explain_wallet SET remaining = remaining + ?, updated_at = CURRENT_TIMESTAMP(6) "
                + "WHERE user_id = ?", userId, PACK_TURNS);
        return status(userId);
    }

    private boolean ensureWallet(Long userId, boolean eligibleForTrial) {
        int seed = eligibleForTrial ? TRIAL_TURNS : 0;
        int inserted = jdbc.update("INSERT IGNORE INTO jpath_ai_explain_wallet "
                + "(user_id, remaining, trial_used, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))",
                userId, seed, eligibleForTrial ? 1 : 0);
        return inserted > 0 && seed > 0;
    }

    private WalletRow readWallet(Long userId) {
        List<WalletRow> rows = jdbc.query(
                "SELECT remaining, trial_used FROM jpath_ai_explain_wallet WHERE user_id = ?",
                (rs, i) -> new WalletRow(rs.getInt(1), rs.getInt(2) != 0),
                userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private int proUsed(Long userId, LocalDate day) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT pro_used FROM jpath_ai_explain_daily WHERE user_id = ? AND usage_date = ?",
                Integer.class, userId, day);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private boolean seenToday(Long userId, LocalDate day, String cacheKey) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jpath_ai_explain_seen WHERE user_id = ? AND usage_date = ? AND cache_key = ?",
                Integer.class, userId, day, cacheKey);
        return n != null && n > 0;
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Đăng nhập để dùng AI giải thích câu hỏi.");
        }
    }
}
