package com.jlas.moderation.service;

import com.jlas.notification.service.UserNotificationPort;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Tells users the outcome of moderation decisions that affect them (JLAS-W0250).
 * Runs inside the caller's transaction, so a rolled-back decision sends nothing.
 * Messages never reveal which moderator decided; the moderator's reason is shown so users
 * can fix the content or file an appeal.
 */
@Component
@RequiredArgsConstructor
public class ModerationNotifier {
    public static final String TYPE_CONTENT = "MODERATION_CONTENT";
    public static final String TYPE_SANCTION = "MODERATION_SANCTION";
    public static final String TYPE_APPEAL = "MODERATION_APPEAL";
    public static final String TYPE_CONTRIBUTION = "DICTIONARY_CONTRIBUTION";

    static final String APPEAL_URL = "/appeal.html";
    static final String CREDIT_URL = "/credit-history";
    private static final int MAX_REASON = 300;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private static final Map<String, String> TARGET_LABELS = Map.of(
            "USER_AVATAR", "Ảnh đại diện",
            "FLASHCARD_SET", "Bộ thẻ", "FLASHCARD_COMMENT", "Bình luận", "VIDEO", "Video Shadowing",
            "VIDEO_COMMENT", "Bình luận", "LESSON", "Bài học", "SCENARIO", "Kịch bản", "TEXTBOOK", "Giáo trình");

    private final UserNotificationPort notifications;

    /** Valid report: content is gone for good. */
    public void contentRemoved(Long ownerId, String targetType, Long targetId, String reason) {
        send(ownerId, TYPE_CONTENT, label(targetType) + " của bạn đã bị gỡ",
                label(targetType) + " của bạn đã bị gỡ vì vi phạm quy định cộng đồng. Lý do: " + clip(reason)
                        + ". Nếu bạn cho rằng đây là nhầm lẫn, hãy gửi kháng nghị.",
                APPEAL_URL, "MOD_REMOVED_" + targetType + "_" + targetId);
    }

    /** Invalid report: content is visible again. */
    public void contentRestored(Long ownerId, String targetType, Long targetId, String targetUrl) {
        send(ownerId, TYPE_CONTENT, label(targetType) + " của bạn đã hiển thị lại",
                label(targetType) + " của bạn đã được kiểm tra và không vi phạm, mọi người có thể xem lại bình thường.",
                targetUrl, "MOD_RESTORED_" + targetType + "_" + targetId);
    }

    /** AI-queue decision. Approving already-visible content is silent; the rest is announced. */
    public void queueDecision(Long ownerId, String targetType, Long targetId, boolean approved, String reason,
                              String targetUrl, Long caseId) {
        boolean prePublish = "FLASHCARD_SET".equals(targetType) || "VIDEO".equals(targetType);
        if (approved && !prePublish) return;
        if (approved) {
            send(ownerId, TYPE_CONTENT, label(targetType) + " của bạn đã được duyệt",
                    label(targetType) + " của bạn đã qua kiểm duyệt và được hiển thị công khai.",
                    targetUrl, "MOD_QUEUE_" + caseId);
            return;
        }
        send(ownerId, TYPE_CONTENT, label(targetType) + " của bạn đã bị ẩn",
                label(targetType) + " của bạn đã bị ẩn sau khi kiểm duyệt. Lý do: " + clip(reason)
                        + ". Nếu bạn cho rằng đây là nhầm lẫn, hãy gửi kháng nghị.",
                APPEAL_URL, "MOD_QUEUE_" + caseId);
    }

    public void contributionDecision(Long contributorId, String word, boolean approved, int reward, String reason,
                                     Long caseId) {
        String subject = word == null || word.isBlank() ? "Từ vựng bạn đóng góp" : "Từ \"" + word + "\"";
        if (approved) {
            String thanks = reward > 0 ? " Bạn nhận được " + reward + " credit, cảm ơn bạn!"
                    : " Hôm nay bạn đã nhận đủ credit cho đóng góp, cảm ơn bạn!";
            send(contributorId, TYPE_CONTRIBUTION, "Đóng góp từ vựng đã được duyệt",
                    subject + " đã được công khai trong từ điển." + thanks,
                    CREDIT_URL, "MOD_CONTRIBUTION_" + caseId);
        } else {
            send(contributorId, TYPE_CONTRIBUTION, "Đóng góp từ vựng chưa được duyệt",
                    subject + " chưa được đưa vào từ điển. Lý do: " + clip(reason) + ".",
                    null, "MOD_CONTRIBUTION_" + caseId);
        }
    }

    public void sanctionApplied(Long userId, Long sanctionId, String type, LocalDateTime endsAt, String reason) {
        String what = "BAN".equals(type) ? "Tài khoản của bạn đã bị khoá" : "Bạn tạm thời không thể bình luận";
        String until = endsAt == null ? "vĩnh viễn" : "đến " + DATE.format(endsAt);
        send(userId, TYPE_SANCTION, what,
                what + " " + until + ". Lý do: " + clip(reason) + ". Bạn có thể gửi kháng nghị nếu thấy chưa đúng.",
                APPEAL_URL, "MOD_SANCTION_" + sanctionId);
    }

    public void sanctionRevoked(Long userId, Long sanctionId) {
        send(userId, TYPE_SANCTION, "Chế tài đã được gỡ",
                "Chế tài trên tài khoản của bạn đã được gỡ. Bạn có thể sử dụng lại đầy đủ các tính năng.",
                null, "MOD_SANCTION_REVOKED_" + sanctionId);
    }

    public void appealResolved(Long userId, Long appealId, boolean approved, String reason) {
        send(userId, TYPE_APPEAL, approved ? "Kháng nghị được chấp nhận" : "Kháng nghị không được chấp nhận",
                approved
                        ? "Kháng nghị #" + appealId + " của bạn đã được chấp nhận, chế tài đã được gỡ. Ghi chú: " + clip(reason) + "."
                        : "Kháng nghị #" + appealId + " của bạn không được chấp nhận, chế tài vẫn giữ nguyên. Lý do: "
                                + clip(reason) + ".",
                APPEAL_URL, "MOD_APPEAL_" + appealId);
    }

    // ── Revised decisions: each revision has its own audit id, so every one of them is announced ──

    public void queueRevised(Long ownerId, String targetType, boolean approved, String reason, String targetUrl,
                             Long revisionId) {
        if (approved) {
            send(ownerId, TYPE_CONTENT, label(targetType) + " của bạn đã hiển thị lại",
                    label(targetType) + " của bạn đã được xem xét lại và không vi phạm, mọi người có thể xem bình thường."
                            + " Ghi chú: " + clip(reason) + ".",
                    targetUrl, "MOD_REVISE_" + revisionId);
        } else {
            send(ownerId, TYPE_CONTENT, label(targetType) + " của bạn đã bị ẩn",
                    label(targetType) + " của bạn đã được xem xét lại và bị ẩn. Lý do: " + clip(reason)
                            + ". Nếu bạn cho rằng đây là nhầm lẫn, hãy gửi kháng nghị.",
                    APPEAL_URL, "MOD_REVISE_" + revisionId);
        }
    }

    /** {@code creditChange} is what was actually added (+) or taken back (−). */
    public void contributionRevised(Long contributorId, String word, boolean approved, int creditChange,
                                    String reason, Long revisionId) {
        String subject = word == null || word.isBlank() ? "Từ vựng bạn đóng góp" : "Từ \"" + word + "\"";
        if (approved) {
            send(contributorId, TYPE_CONTRIBUTION, "Đóng góp từ vựng đã được duyệt",
                    subject + " đã được xem xét lại và công khai trong từ điển."
                            + (creditChange > 0 ? " Bạn nhận được " + creditChange + " credit." : ""),
                    creditChange != 0 ? CREDIT_URL : null, "MOD_REVISE_" + revisionId);
        } else {
            send(contributorId, TYPE_CONTRIBUTION, "Đóng góp từ vựng đã bị gỡ khỏi từ điển",
                    subject + " đã được xem xét lại và gỡ khỏi từ điển. Lý do: " + clip(reason) + "."
                            + (creditChange < 0 ? " Đã thu hồi " + (-creditChange) + " credit thưởng." : ""),
                    creditChange != 0 ? CREDIT_URL : null, "MOD_REVISE_" + revisionId);
        }
    }

    /** {@code reinstatedUntil} is set when a rejection put the sanction back; null when it had already ended. */
    public void appealRevised(Long userId, Long appealId, boolean approved, boolean reinstated,
                              LocalDateTime sanctionEndsAt, String reason, Long revisionId) {
        if (approved) {
            send(userId, TYPE_APPEAL, "Kháng nghị được chấp nhận",
                    "Kháng nghị #" + appealId + " của bạn đã được xem xét lại và chấp nhận, chế tài đã được gỡ."
                            + " Ghi chú: " + clip(reason) + ".",
                    APPEAL_URL, "MOD_REVISE_" + revisionId);
            return;
        }
        String effect = !reinstated ? " Chế tài đã hết hạn nên không áp lại."
                : " Chế tài được áp lại " + (sanctionEndsAt == null ? "vĩnh viễn" : "đến " + DATE.format(sanctionEndsAt)) + ".";
        send(userId, TYPE_APPEAL, "Kháng nghị không được chấp nhận",
                "Kháng nghị #" + appealId + " của bạn đã được xem xét lại và không được chấp nhận." + effect
                        + " Lý do: " + clip(reason) + ".",
                APPEAL_URL, "MOD_REVISE_" + revisionId);
    }

    private void send(Long userId, String type, String title, String message, String url, String eventKey) {
        if (userId == null) return;
        notifications.notifyUser(userId, type, title, message, url, eventKey);
    }

    private static String label(String targetType) {
        return TARGET_LABELS.getOrDefault(targetType, "Nội dung");
    }

    private static String clip(String reason) {
        String value = reason == null ? "" : reason.trim().replaceAll("[.。!！]+$", "");
        return value.length() > MAX_REASON ? value.substring(0, MAX_REASON - 1) + "…" : value;
    }
}
