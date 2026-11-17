package com.jlas.moderation.credit.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Không hỏi lại hôm nay" trên popup trả CP, lưu theo tài khoản (bảng cp_auto_pay, migration 079) để bật ở máy này
 * thì máy khác cũng biết. Mỗi chức năng bật riêng trong ngày và tự dùng tối đa {@value #DAILY_CAP} CP; số CP đã tự dùng
 * đọc từ sổ CP kể từ lúc bật, nên không lệch giữa các máy. Máy chủ vẫn tự kiểm tra số dư mỗi lần trừ.
 */
@Service
@RequiredArgsConstructor
public class CpAutoPayService {
    public static final int DAILY_CAP = 100;

    /** Each feature the popup offers, with the ledger reason its CP is spent under. */
    static final Map<String, CreditReason> FEATURES = Map.of(
            "aiCall", CreditReason.AI_CALL_TURN,
            "pronunciation", CreditReason.PRONUNCIATION_CHECK,
            "quickLookup", CreditReason.QUICK_LOOKUP_PACK,
            "shadowingCreate", CreditReason.SHADOWING_VIDEO_CREATE);

    private final JdbcTemplate jdbc;

    /** {@code spent}: CP paid for this feature since it was turned on today; {@code cost}: price of one use. */
    public record State(boolean on, int spent, int cost, int cap) {
        static State off() {
            return new State(false, 0, 0, DAILY_CAP);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, State> today(Long userId) {
        Map<String, State> states = new LinkedHashMap<>();
        FEATURES.keySet().stream().sorted().forEach(feature -> states.put(feature, State.off()));
        jdbc.query("SELECT feature, unit_cost, enabled_at FROM cp_auto_pay WHERE user_id = ? AND pay_date = ?",
                row -> {
                    String feature = row.getString("feature");
                    CreditReason reason = FEATURES.get(feature);
                    if (reason != null) {
                        states.put(feature, new State(true, spentSince(userId, reason, row.getTimestamp("enabled_at")),
                                row.getInt("unit_cost"), DAILY_CAP));
                    }
                }, userId, LocalDate.now());
        return states;
    }

    /** Turns a feature on (a new confirmation starts a new {@value #DAILY_CAP} CP cap) or off for today. */
    @Transactional
    public Map<String, State> set(Long userId, String feature, boolean on, int cost) {
        if (!FEATURES.containsKey(feature)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chức năng không hợp lệ.");
        }
        LocalDate today = LocalDate.now();
        if (on) {
            int unitCost = Math.max(0, Math.min(cost, DAILY_CAP));
            int updated = jdbc.update("UPDATE cp_auto_pay SET unit_cost = ?, enabled_at = CURRENT_TIMESTAMP(6) "
                    + "WHERE user_id = ? AND feature = ? AND pay_date = ?", unitCost, userId, feature, today);
            if (updated == 0) {
                jdbc.update("INSERT IGNORE INTO cp_auto_pay (user_id, feature, pay_date, unit_cost, enabled_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))", userId, feature, today, unitCost);
            }
        } else {
            jdbc.update("DELETE FROM cp_auto_pay WHERE user_id = ? AND feature = ? AND pay_date = ?",
                    userId, feature, today);
        }
        return today(userId);
    }

    private int spentSince(Long userId, CreditReason reason, Timestamp since) {
        List<Integer> rows = jdbc.queryForList("SELECT COALESCE(-SUM(amount), 0) FROM user_credit_transaction "
                + "WHERE user_id = ? AND reason = ? AND amount < 0 AND created_at >= ?", Integer.class,
                userId, reason.name(), since);
        return rows.isEmpty() || rows.getFirst() == null ? 0 : rows.getFirst();
    }
}
