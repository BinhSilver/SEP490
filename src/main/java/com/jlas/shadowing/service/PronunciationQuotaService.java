package com.jlas.shadowing.service;

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
 * Lượt chấm phát âm mỗi ngày (kế hoạch Pro/CP): {@value #FREE_DAILY} lượt miễn phí, Pro tới {@value #PRO_DAILY};
 * quá lượt thì {@value #CREDIT_PRICE} CP/lần khi người học xác nhận trên popup. Một lần chấm lỗi được hoàn lại.
 * Bảng pronunciation_usage_daily đến từ migration 066.
 */
@Service
@RequiredArgsConstructor
public class PronunciationQuotaService {
    public static final int FREE_DAILY = 5;
    public static final int PRO_DAILY = 100;
    public static final int CREDIT_PRICE = 5;
    static final String CREDIT_REFERENCE = "PRONUNCIATION_CHECK";

    private final JdbcTemplate jdbc;
    private final PremiumAccess premium;
    private final UserCreditService credits;

    /** How one check was paid for, so a failed check can be given back. */
    public record Charge(Long userId, String kind, LocalDate usageDate) {
        static final String FREE = "FREE";
        static final String QUOTA = "QUOTA";
        static final String CREDIT = "CREDIT";
    }

    /** What the popup shows: checks used today out of the daily allowance, the price after it and the balance. */
    public record Status(int usedToday, int dailyLimit, boolean pro, int creditPrice, long creditBalance) {
    }

    @Transactional(readOnly = true)
    public Status status(Long userId) {
        boolean pro = premium.isActive(userId);
        return new Status(used(userId, LocalDate.now()), pro ? PRO_DAILY : FREE_DAILY, pro, CREDIT_PRICE,
                credits.balance(userId));
    }

    /**
     * Takes one check from today's allowance or, past it and with {@code useCredit}, {@value #CREDIT_PRICE} CP.
     * Throws PRONUNCIATION_QUOTA_EXCEEDED so the page can ask first, INSUFFICIENT_CREDIT when short of CP.
     */
    @Transactional
    public Charge reserve(Long userId, boolean useCredit) {
        if (ContentReviewAccess.allowed(userId)) {
            return new Charge(userId, Charge.FREE, null);
        }
        LocalDate today = LocalDate.now();
        int limit = premium.isActive(userId) ? PRO_DAILY : FREE_DAILY;
        jdbc.update("INSERT IGNORE INTO pronunciation_usage_daily (user_id, usage_date, attempts) VALUES (?, ?, 0)",
                userId, today);
        // The limit sits in the WHERE clause: a row already at the limit is not matched, whatever the driver's
        // found-rows setting, and two checks at once cannot both take the last slot.
        int taken = jdbc.update("UPDATE pronunciation_usage_daily SET attempts = attempts + 1 "
                + "WHERE user_id = ? AND usage_date = ? AND attempts < ?", userId, today, limit);
        if (taken > 0) {
            return new Charge(userId, Charge.QUOTA, today);
        }
        if (!useCredit) {
            throw new ApiException(ErrorCode.PRONUNCIATION_QUOTA_EXCEEDED,
                    "Bạn đã dùng hết " + limit + " lượt chấm phát âm hôm nay. Chấm thêm tốn " + CREDIT_PRICE + " CP/lần.");
        }
        credits.spend(userId, CREDIT_PRICE, CreditReason.PRONUNCIATION_CHECK, null, null, "Chấm phát âm 1 câu");
        return new Charge(userId, Charge.CREDIT, today);
    }

    /** Gives back a check that failed (Azure error, bad recording): the allowance slot or the CP. */
    @Transactional
    public void release(Charge charge) {
        if (charge == null) {
            return;
        }
        switch (charge.kind()) {
            case Charge.QUOTA -> jdbc.update("UPDATE pronunciation_usage_daily SET attempts = attempts - 1 "
                    + "WHERE user_id = ? AND usage_date = ? AND attempts > 0", charge.userId(), charge.usageDate());
            case Charge.CREDIT -> credits.earn(charge.userId(), CREDIT_PRICE, CreditReason.REFUND, null, null,
                    "Hoàn CP: chấm phát âm bị lỗi");
            default -> { }
        }
    }

    private int used(Long userId, LocalDate day) {
        List<Integer> rows = jdbc.queryForList("SELECT attempts FROM pronunciation_usage_daily WHERE user_id = ? "
                + "AND usage_date = ?", Integer.class, userId, day);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }
}
