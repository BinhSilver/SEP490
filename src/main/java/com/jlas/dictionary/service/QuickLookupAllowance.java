package com.jlas.dictionary.service;

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
 * Lượt tra từ nhanh (bôi đen để tra). Pro and content reviewers look words up freely; everyone else spends
 * lookups bought with CP, {@value #PACK_LOOKUPS} for {@value #PACK_PRICE} CP. The same word again on the same day
 * is free, so reopening a popup never costs twice. Tables come from migration 066.
 */
@Service
@RequiredArgsConstructor
public class QuickLookupAllowance {
    public static final int PACK_LOOKUPS = 10;
    public static final int PACK_PRICE = 5;

    private final JdbcTemplate jdbc;
    private final PremiumAccess premium;
    private final UserCreditService credits;

    /** What the popup shows before a purchase: lookups left, CP balance and the pack on offer. */
    public record Status(boolean unlimited, int remaining, long creditBalance, int packLookups, int packPrice) {
    }

    @Transactional(readOnly = true)
    public Status status(Long userId) {
        requireUser(userId);
        return new Status(unlimited(userId), remaining(userId), credits.balance(userId), PACK_LOOKUPS, PACK_PRICE);
    }

    /** Uses one lookup for {@code vocabularyId}; throws QUICK_LOOKUP_EXHAUSTED when none is left. */
    @Transactional
    public void consume(Long userId, Long vocabularyId) {
        requireUser(userId);
        if (unlimited(userId)) {
            return;
        }
        LocalDate today = LocalDate.now();
        Integer seen = jdbc.queryForObject("SELECT COUNT(*) FROM quick_lookup_daily "
                + "WHERE user_id = ? AND lookup_date = ? AND vocabulary_id = ?", Integer.class, userId, today, vocabularyId);
        if (seen != null && seen > 0) {
            return;
        }
        int used = jdbc.update("UPDATE quick_lookup_wallet SET remaining = remaining - 1, updated_at = CURRENT_TIMESTAMP(6) "
                + "WHERE user_id = ? AND remaining > 0", userId);
        if (used == 0) {
            throw new ApiException(ErrorCode.QUICK_LOOKUP_EXHAUSTED);
        }
        jdbc.update("INSERT IGNORE INTO quick_lookup_daily (user_id, lookup_date, vocabulary_id) VALUES (?, ?, ?)",
                userId, today, vocabularyId);
    }

    /** Spends {@value #PACK_PRICE} CP for {@value #PACK_LOOKUPS} more lookups; INSUFFICIENT_CREDIT when short. */
    @Transactional
    public Status buyPack(Long userId) {
        requireUser(userId);
        credits.spend(userId, PACK_PRICE, CreditReason.QUICK_LOOKUP_PACK, null, null,
                "Mua " + PACK_LOOKUPS + " lượt tra từ nhanh");
        jdbc.update("INSERT INTO quick_lookup_wallet (user_id, remaining, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP(6)) "
                + "ON DUPLICATE KEY UPDATE remaining = remaining + ?, updated_at = CURRENT_TIMESTAMP(6)",
                userId, PACK_LOOKUPS, PACK_LOOKUPS);
        return status(userId);
    }

    private boolean unlimited(Long userId) {
        return ContentReviewAccess.allowed(userId) || premium.isActive(userId);
    }

    private int remaining(Long userId) {
        List<Integer> rows = jdbc.queryForList("SELECT remaining FROM quick_lookup_wallet WHERE user_id = ?",
                Integer.class, userId);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Đăng nhập để dùng tra từ nhanh.");
        }
    }
}
