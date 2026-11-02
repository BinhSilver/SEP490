package com.jlas.community.service;

import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.handler.CommunitySource;
import com.jlas.moderation.service.ModerationNotifier;
import com.jlas.notification.service.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Thông báo của Community, chỉ gửi cho người liên quan: chủ nội dung khi có bình luận mới, chủ bình luận khi
 * bình luận bị báo cáo. Không bao giờ nêu ai là người báo cáo. Chạy trong transaction của thao tác gốc nên
 * thao tác bị rollback thì không gửi gì. Kết quả kiểm duyệt (gỡ / khôi phục) do ModerationNotifier gửi.
 */
@Component
@RequiredArgsConstructor
public class CommunityNotifier {
    private static final int MAX_TITLE_IN_MESSAGE = 120;

    private final UserNotificationPort notifications;

    /** Bình luận mới: báo chủ nội dung (trừ khi chính chủ tự bình luận). */
    public void newComment(CommunitySource source, Long commentId, Long authorId, String authorName) {
        Long ownerId = source.ownerId();
        if (ownerId == null || Objects.equals(ownerId, authorId)) return;
        notifications.notifyUser(ownerId, commentType(source.contentType()), "Bình luận mới trong Cộng đồng",
                authorName + " đã bình luận về “" + clip(source.title()) + "”.",
                url(source), "COMMUNITY_COMMENT_" + commentId);
    }

    /** Bình luận bị báo cáo: báo chủ bình luận, không nêu người báo cáo. */
    public void commentReported(Long commentOwnerId, CommunitySource source, Long reportId, boolean hidden) {
        if (commentOwnerId == null) return;
        String message = "Bình luận của bạn trong “" + clip(source.title()) + "” đã bị báo cáo"
                + (hidden ? " và đang tạm ẩn chờ kiểm duyệt viên xem xét." : ", kiểm duyệt viên sẽ xem xét.");
        notifications.notifyUser(commentOwnerId, ModerationNotifier.TYPE_CONTENT, "Bình luận của bạn bị báo cáo",
                message, url(source), "COMMUNITY_COMMENT_REPORT_" + reportId);
    }

    /** Dùng lại loại thông báo bình luận đã có để chuông/lịch sử hiện đúng icon. */
    private static String commentType(String contentType) {
        return CommunityContentType.SHADOWING_VIDEO.equals(contentType) ? "SHADOWING_COMMENT" : "FLASHCARD_COMMENT";
    }

    private static String url(CommunitySource source) {
        return "/community/" + source.contentType() + "/" + source.contentId();
    }

    private static String clip(String title) {
        String value = title == null ? "" : title.trim();
        return value.length() <= MAX_TITLE_IN_MESSAGE ? value : value.substring(0, MAX_TITLE_IN_MESSAGE) + "…";
    }
}
