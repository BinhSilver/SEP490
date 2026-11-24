package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.FeedItem;
import com.jlas.community.entity.CommunityComment;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.enums.CommunityStatus;
import com.jlas.community.repository.CommunityCommentRepository;
import com.jlas.community.repository.CommunityContentRepository;
import com.jlas.community.service.CommunityAccessService.Target;
import com.jlas.moderation.report.ReportDecision;
import com.jlas.moderation.service.ModerationAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * MODERATOR/ADMIN/CONTENT_CREATOR gỡ trực tiếp bài viết / bình luận cộng đồng (không cần chờ đủ báo cáo). Dùng chung cơ chế với hàng
 * chờ báo cáo: trạng thái REMOVED trên community_content / community_comment (không xoá cứng), báo cáo đang mở của đối
 * tượng được đóng là RESOLVED ({@link CommunityReportedContentPort#resolve}), ghi nhật ký kiểm duyệt
 * (moderation_audit_log, cùng transaction) và báo cho tác giả kèm lý do.
 */
@Service
@RequiredArgsConstructor
public class CommunityModerationService {
    /** Hành động trong moderation_audit_log. */
    public static final String ACTION_REMOVE = "COMMUNITY_REMOVE";
    public static final int MAX_REASON = 500;

    private final CommunityAccessService access;
    private final CommunityContentRepository contents;
    private final CommunityCommentRepository comments;
    private final CommunityReportedContentPort reportedContent;
    private final ModerationAuditService audit;
    private final CommunityNotifier notifier;
    private final CommunityFeedService feed;

    /** Gỡ bài viết; trả mục feed của bài (trạng thái REMOVED + lý do) cho người gỡ. */
    @Transactional
    public FeedItem removePost(Long postId, Long moderatorId, String reason) {
        requireModerator();
        String why = reason(reason);
        Target target = access.load(CommunityContentType.POST, postId);
        contents.ensureRow(CommunityContentType.POST, postId);
        // Kiểm tra trên bản đọc lại sau khi khoá: hai lần gỡ đồng thời (hoặc gỡ cùng lúc xử lý hàng chờ) thì lần sau 409.
        CommunityContent row = access.fresh(contents.lockBySource(CommunityContentType.POST, postId)
                .orElseThrow(CommunityAccessService::notFound));
        if (CommunityStatus.REMOVED.name().equals(row.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Bài viết đã bị gỡ");
        }
        removeLocked(row, target, moderatorId, why);
        return feed.item(access.load(CommunityContentType.POST, postId), moderatorId);
    }

    /**
     * Gỡ một nội dung cộng đồng theo id dòng community_content (bài viết, bộ thẻ chia sẻ…), dùng cho trang "Báo cáo cộng
     * đồng" của người biên soạn: gỡ được cả nội dung mới có 1 báo cáo lẫn nội dung đang tạm ẩn.
     */
    @Transactional
    public void removeContent(Long communityContentId, Long moderatorId, String reason) {
        requireModerator();
        String why = reason(reason);
        CommunityContent row = access.fresh(contents.lockById(communityContentId)
                .orElseThrow(CommunityAccessService::notFound));
        if (CommunityStatus.REMOVED.name().equals(row.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Nội dung đã bị gỡ");
        }
        removeLocked(row, access.load(row), moderatorId, why);
    }

    /** Dòng đã khoá + đọc lại, chưa REMOVED: gỡ, đóng báo cáo đang mở, ghi nhật ký và báo tác giả. */
    private void removeLocked(CommunityContent row, Target target, Long moderatorId, String why) {
        String from = row.getStatus();
        reportedContent.resolve(CommunityReportedContentPort.CONTENT, row.getId(), decision(moderatorId, why));
        audit.record(moderatorId, new ModerationAuditService.Entry(ACTION_REMOVE, CommunityReportedContentPort.CONTENT,
                row.getId(), from, CommunityStatus.REMOVED.name(), why, null));
        // Chỉ tác giả bài viết (POST) còn xem được nội dung đã gỡ; nguồn khác link về trang Cộng đồng.
        notifier.removedByModerator(target.source().ownerId(), target.source(), null, why,
                CommunityContentType.POST.equals(row.getContentType()));
    }

    /** Gỡ một bình luận cộng đồng (trên bài viết hoặc nội dung chia sẻ). */
    @Transactional
    public void removeComment(Long commentId, Long moderatorId, String reason) {
        requireModerator();
        String why = reason(reason);
        CommunityComment existing = comments.findById(commentId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(CommunityAccessService::notFound);
        // Khoá nội dung trước rồi tới bình luận (cùng thứ tự với bình luận/báo cáo, tránh deadlock) và kiểm tra trên bản
        // đọc lại sau khi khoá.
        CommunityContent row = access.fresh(contents.lockById(existing.getCommunityContentId())
                .orElseThrow(CommunityAccessService::notFound));
        CommunityComment comment = access.fresh(comments.lockById(commentId)
                .orElseThrow(CommunityAccessService::notFound));
        if (comment.getDeletedAt() != null) throw CommunityAccessService.notFound();
        if (CommunityStatus.REMOVED.name().equals(comment.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Bình luận đã bị gỡ");
        }
        Target target = access.load(row);
        String from = comment.getStatus();
        reportedContent.resolve(CommunityReportedContentPort.COMMENT, commentId, decision(moderatorId, why));
        audit.record(moderatorId, new ModerationAuditService.Entry(ACTION_REMOVE, CommunityReportedContentPort.COMMENT,
                commentId, from, CommunityStatus.REMOVED.name(), why, null));
        // Nội dung gốc đang ẩn/đã gỡ với người bình luận: thông báo không trích nội dung, không gắn link sẽ trả 404.
        boolean commenterSeesSource = access.canView(target, comment.getUserId(), false);
        notifier.removedByModerator(comment.getUserId(), target.source(), commentId, why, commenterSeesSource);
    }

    private static ReportDecision decision(Long moderatorId, String reason) {
        return new ReportDecision(true, CommunityStatus.REMOVED.name(), moderatorId, reason, LocalDateTime.now());
    }

    private static void requireModerator() {
        if (!CommunityAccessService.isModerator()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Chỉ người biên soạn, kiểm duyệt viên hoặc quản trị viên được gỡ nội dung");
        }
    }

    /** Lý do bắt buộc, 1-500 ký tự sau khi bỏ khoảng trắng hai đầu. */
    static String reason(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Vui lòng nhập lý do gỡ");
        if (text.length() > MAX_REASON) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Lý do tối đa " + MAX_REASON + " ký tự");
        }
        return text;
    }
}
