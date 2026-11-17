package com.jlas.shadowing.service;

import com.jlas.auth.service.PremiumAccess;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.common.security.ContentReviewAccess;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Số phút video Shadowing tạo mỗi tuần (kế hoạch Pro/CP): {@value #FREE_WEEKLY_MINUTES} phút miễn phí, Pro tới
 * {@value #PRO_WEEKLY_MINUTES}; phần vượt {@value #CREDIT_PER_MINUTE} CP/phút khi người dùng xác nhận trên popup (video 20
 * phút = 1.000 CP). Chỉ phút có dùng AI mới tính, xem {@link #billableMinutes}. Tuần bắt đầu từ thứ Hai.
 * <p>
 * Phút và CP được trừ trong cùng giao dịch tạo video, nên video không bắt đầu được thì tự hoàn. Khoản đã trừ được ghi
 * theo video (shadowing_create_charge): xoá một video chưa xử lý xong thì được hoàn, bấm "Thử lại" không trừ thêm.
 * Bảng đến từ migration 079.
 */
@Service
@RequiredArgsConstructor
public class ShadowingCreateQuotaService {
    public static final int FREE_WEEKLY_MINUTES = 20;
    public static final int PRO_WEEKLY_MINUTES = 120;
    public static final int CREDIT_PER_MINUTE = 50;
    /** Chuyển giọng nói thành chữ (Deepgram) tốn khoảng gấp đôi phần AI phụ đề, nên mỗi phút tính thành 3 phút. */
    public static final int SPEECH_TO_TEXT_FACTOR = 3;

    private final JdbcTemplate jdbc;
    private final PremiumAccess premium;
    private final UserCreditService credits;

    /** What one video used: minutes from this week's allowance and CP for the rest. */
    public record Charge(Long userId, LocalDate weekStart, int quotaMinutes, int creditPaid) {
        static Charge none(Long userId) {
            return new Charge(userId, null, 0, 0);
        }
    }

    /** What the page shows: minutes used this week out of the allowance, the price per extra minute and the balance. */
    public record Status(int usedMinutes, int weeklyMinutes, boolean pro, boolean unlimited, int creditPerMinute,
                         int speechToTextFactor, long creditBalance) {
    }

    /**
     * Minutes a video counts for: its length rounded up to whole minutes, times {@value #SPEECH_TO_TEXT_FACTOR} when the
     * speech has to be transcribed. A YouTube video that only uses its own subtitles costs no AI and counts 0.
     */
    public static int billableMinutes(double durationSeconds, boolean aiSubtitles, boolean speechToText) {
        if ((!aiSubtitles && !speechToText) || durationSeconds <= 0) {
            return 0;
        }
        int minutes = (int) Math.ceil(durationSeconds / 60.0);
        return speechToText ? minutes * SPEECH_TO_TEXT_FACTOR : minutes;
    }

    @Transactional(readOnly = true)
    public Status status(Long userId) {
        boolean pro = premium.isActive(userId);
        return new Status(used(userId, weekStart()), pro ? PRO_WEEKLY_MINUTES : FREE_WEEKLY_MINUTES, pro,
                ContentReviewAccess.allowed(userId), CREDIT_PER_MINUTE, SPEECH_TO_TEXT_FACTOR, credits.balance(userId));
    }

    /**
     * Takes {@code minutes} from this week's allowance and charges {@value #CREDIT_PER_MINUTE} CP for each minute past it.
     * Without {@code useCredit} a video that would cost CP throws SHADOWING_CREATE_QUOTA_EXCEEDED, so the page can show the
     * price first; INSUFFICIENT_CREDIT when short of CP. Runs inside the caller's transaction, so a video that fails to
     * start gives everything back. Content creators and admins make the shared library and are not counted.
     */
    @Transactional
    public Charge reserve(Long userId, int minutes, boolean useCredit) {
        if (userId == null || minutes <= 0 || ContentReviewAccess.allowed(userId)) {
            return Charge.none(userId);
        }
        LocalDate week = weekStart();
        int limit = premium.isActive(userId) ? PRO_WEEKLY_MINUTES : FREE_WEEKLY_MINUTES;
        jdbc.update("INSERT IGNORE INTO shadowing_minutes_weekly (user_id, week_start, minutes_used) VALUES (?, ?, 0)",
                userId, week);
        // Locks the row, so two videos started at once cannot both use the same free minutes.
        Integer used = jdbc.queryForObject("SELECT minutes_used FROM shadowing_minutes_weekly WHERE user_id = ? "
                + "AND week_start = ? FOR UPDATE", Integer.class, userId, week);
        int fromQuota = Math.min(minutes, Math.max(0, limit - (used == null ? 0 : used)));
        int credit = (minutes - fromQuota) * CREDIT_PER_MINUTE;
        if (credit > 0 && !useCredit) {
            throw new ApiException(ErrorCode.SHADOWING_CREATE_QUOTA_EXCEEDED, "Video này tính " + minutes
                    + " phút, tuần này còn " + fromQuota + " phút miễn phí. Phần vượt tốn " + credit + " CP ("
                    + CREDIT_PER_MINUTE + " CP/phút).");
        }
        if (fromQuota > 0) {
            jdbc.update("UPDATE shadowing_minutes_weekly SET minutes_used = minutes_used + ? WHERE user_id = ? "
                    + "AND week_start = ?", fromQuota, userId, week);
        }
        if (credit > 0) {
            credits.spend(userId, credit, CreditReason.SHADOWING_VIDEO_CREATE, null, null,
                    "Tạo video Shadowing: " + (minutes - fromQuota) + " phút vượt hạn mức");
        }
        return new Charge(userId, week, fromQuota, credit);
    }

    /** Remembers what this video used, so deleting it before it is ready gives it back. */
    @Transactional
    public void attach(Charge charge, Long videoId) {
        if (charge == null || videoId == null || charge.weekStart() == null) {
            return;
        }
        jdbc.update("INSERT INTO shadowing_create_charge (video_id, user_id, week_start, quota_minutes, credit_paid, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
                videoId, charge.userId(), charge.weekStart(), charge.quotaMinutes(), charge.creditPaid());
    }

    /** CP paid for a video, for the page that started it. */
    @Transactional(readOnly = true)
    public int creditPaid(Long videoId) {
        List<Integer> rows = jdbc.queryForList("SELECT credit_paid FROM shadowing_create_charge WHERE video_id = ?",
                Integer.class, videoId);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    /** The owner deleted a video that never finished processing: give back its minutes and CP, once. */
    @Transactional
    public void refundUnfinished(Long videoId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id, week_start, quota_minutes, credit_paid "
                + "FROM shadowing_create_charge WHERE video_id = ? AND refunded_at IS NULL FOR UPDATE", videoId);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> row = rows.getFirst();
        jdbc.update("UPDATE shadowing_create_charge SET refunded_at = CURRENT_TIMESTAMP(6) WHERE video_id = ?", videoId);
        Long userId = ((Number) row.get("user_id")).longValue();
        int minutes = ((Number) row.get("quota_minutes")).intValue();
        int credit = ((Number) row.get("credit_paid")).intValue();
        if (minutes > 0) {
            jdbc.update("UPDATE shadowing_minutes_weekly SET minutes_used = GREATEST(0, minutes_used - ?) "
                    + "WHERE user_id = ? AND week_start = ?", minutes, userId, row.get("week_start"));
        }
        if (credit > 0) {
            credits.earn(userId, credit, CreditReason.REFUND, null, null, "Hoàn CP: xoá video Shadowing chưa xử lý xong");
        }
    }

    static LocalDate weekStart() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private int used(Long userId, LocalDate week) {
        List<Integer> rows = jdbc.queryForList("SELECT minutes_used FROM shadowing_minutes_weekly WHERE user_id = ? "
                + "AND week_start = ?", Integer.class, userId, week);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }
}
