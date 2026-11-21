package com.jlas.community.service;

import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.handler.CommunityPostHandler;
import com.jlas.community.handler.CommunitySource;
import com.jlas.moderation.service.ModerationNotifier;
import com.jlas.notification.service.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Thông báo của Community, chỉ gửi cho người liên quan: chủ nội dung khi có bình luận mới hoặc nội dung bị báo
 * cáo, chủ bình luận khi bình luận bị báo cáo, tác giả khi kiểm duyệt viên gỡ trực tiếp. Không bao giờ nêu ai là người
 * báo cáo. Chạy trong transaction của thao tác gốc nên thao tác bị rollback thì không gửi gì. Kết quả xử lý báo cáo
 * (gỡ / khôi phục qua hàng chờ) do ModerationNotifier gửi.
 */
@Component
@RequiredArgsConstructor
public class CommunityNotifier {
    /** Bình luận mới trên bài viết cộng đồng (POST). Bộ thẻ / video giữ FLASHCARD_COMMENT / SHADOWING_COMMENT như cũ. */
    public static final String TYPE_COMMENT = "COMMUNITY_COMMENT";
    /** Kiểm duyệt viên gỡ trực tiếp bài viết / bình luận cộng đồng. */
    public static final String TYPE_REMOVED = "COMMUNITY_REMOVED";
    static final String COMMUNITY_HOME = "/community";
    private static final int MAX_TITLE_IN_MESSAGE = 120;
    private static final int MAX_REASON_IN_MESSAGE = 300;

    private final UserNotificationPort notifications;

    /** Bình luận mới: báo chủ nội dung (trừ khi chính chủ tự bình luận). */
    public void newComment(CommunitySource source, Long commentId, Long authorId, String authorName) {
        Long ownerId = source.ownerId();
        if (ownerId == null || Objects.equals(ownerId, authorId)) return;
        String message = CommunityContentType.POST.equals(source.contentType())
                ? authorName + " đã bình luận bài viết của bạn: “" + clip(source.title()) + "”."
                : authorName + " đã bình luận về “" + clip(source.title()) + "”.";
        notifications.notifyUser(ownerId, commentType(source.contentType()), "Bình luận mới trong Cộng đồng",
                message, url(source), "COMMUNITY_COMMENT_" + commentId);
    }

    /** Bình luận bị báo cáo: báo chủ bình luận, không nêu người báo cáo. */
    public void commentReported(Long commentOwnerId, CommunitySource source, Long reportId, boolean hidden) {
        if (commentOwnerId == null) return;
        String message = "Bình luận của bạn trong “" + clip(source.title()) + "” đã bị báo cáo"
                + (hidden ? " và đang tạm ẩn chờ kiểm duyệt viên xem xét." : ", kiểm duyệt viên sẽ xem xét.");
        notifications.notifyUser(commentOwnerId, ModerationNotifier.TYPE_CONTENT, "Bình luận của bạn bị báo cáo",
                message, url(source), "COMMUNITY_COMMENT_REPORT_" + reportId);
    }

    /**
     * Nội dung bị báo cáo: báo chủ nội dung ngay (cả bộ thẻ lẫn video Shadowing), không nêu người báo cáo.
     * {@code reportKey} định danh lượt báo cáo (id community_report, hoặc với báo cáo chuyển sang nguồn là người báo
     * cáo + thời điểm, vì nguồn cho báo cáo lại sau khi đã xử lý) để mỗi lượt chỉ sinh một thông báo.
     */
    public void contentReported(CommunitySource source, Long reporterId, String reportKey, boolean hidden) {
        Long ownerId = source.ownerId();
        if (ownerId == null || Objects.equals(ownerId, reporterId)) return;
        String label = CommunityContentType.label(source.contentType());
        String message = label + " “" + clip(source.title()) + "” của bạn đã bị báo cáo"
                + (hidden ? " và đang tạm ẩn chờ kiểm duyệt viên xem xét." : ", kiểm duyệt viên sẽ xem xét.");
        notifications.notifyUser(ownerId, ModerationNotifier.TYPE_CONTENT, label + " của bạn bị báo cáo", message,
                url(source), "COMMUNITY_CONTENT_REPORT_" + source.contentType() + "_" + source.contentId()
                        + "_" + reportKey);
    }

    /**
     * Kiểm duyệt viên gỡ trực tiếp: báo tác giả kèm lý do, link về trang nội dung (tác giả bài viết vẫn xem được bài ở
     * trạng thái "đã bị gỡ"). {@code commentId} null = gỡ cả nội dung; khác null = gỡ một bình luận trên nội dung đó.
     * {@code showSource} false khi người nhận không còn xem được nội dung gốc (vd bình luận trên bài đang ẩn/đã gỡ):
     * không trích nội dung gốc và link về trang Cộng đồng thay vì trang sẽ trả 404.
     */
    public void removedByModerator(Long authorId, CommunitySource source, Long commentId, String reason,
                                   boolean showSource) {
        if (authorId == null) return;
        boolean comment = commentId != null;
        String what = comment ? "Bình luận của bạn" : CommunityContentType.label(source.contentType()) + " của bạn";
        String where = !showSource ? "" : comment ? " trong “" + clip(source.title()) + "”" : " “" + clip(source.title()) + "”";
        notifications.notifyUser(authorId, TYPE_REMOVED, what + " đã bị gỡ",
                what + where + " đã bị kiểm duyệt viên gỡ khỏi Cộng đồng. Lý do: " + clipReason(reason) + ".",
                showSource ? url(source) : COMMUNITY_HOME, comment ? "COMMUNITY_REMOVED_COMMENT_" + commentId
                        : "COMMUNITY_REMOVED_" + source.contentType() + "_" + source.contentId());
    }

    /** Loại thông báo bình luận theo nguồn để chuông/lịch sử hiện đúng icon. */
    static String commentType(String contentType) {
        if (CommunityContentType.POST.equals(contentType)) return TYPE_COMMENT;
        return CommunityContentType.SHADOWING_VIDEO.equals(contentType) ? "SHADOWING_COMMENT" : "FLASHCARD_COMMENT";
    }

    private static String clipReason(String reason) {
        String value = reason == null ? "" : reason.trim().replaceAll("[.。!！]+$", "");
        return CommunityPostHandler.cut(value, MAX_REASON_IN_MESSAGE);
    }

    private static String url(CommunitySource source) {
        return CommunityContentType.pageUrl(source.contentType(), source.contentId());
    }

    private static String clip(String title) {
        return CommunityPostHandler.cut(title == null ? "" : title.trim(), MAX_TITLE_IN_MESSAGE);
    }
}
