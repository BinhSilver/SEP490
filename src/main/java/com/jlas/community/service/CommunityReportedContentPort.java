package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.entity.CommunityComment;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.entity.CommunityReport;
import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.enums.CommunityStatus;
import com.jlas.community.enums.ReportReason;
import com.jlas.community.enums.ReportStatus;
import com.jlas.community.enums.ReportTargetType;
import com.jlas.community.handler.CommunityPostHandler;
import com.jlas.community.handler.CommunitySource;
import com.jlas.community.repository.CommunityCommentRepository;
import com.jlas.community.repository.CommunityContentRepository;
import com.jlas.community.repository.CommunityReportRepository;
import com.jlas.moderation.report.ReportDecision;
import com.jlas.moderation.report.ReportedContentPort;
import com.jlas.moderation.report.ReportedTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Đưa nội dung/bình luận cộng đồng bị tạm ẩn vào hàng chờ /admin/moderation/reports. ReportReviewService gọi
 * {@link #resolve}: báo cáo đúng → REMOVED + báo cáo RESOLVED, báo cáo sai → PUBLISHED + báo cáo REJECTED.
 */
@Component
@RequiredArgsConstructor
public class CommunityReportedContentPort implements ReportedContentPort {
    public static final String CONTENT = "COMMUNITY_CONTENT";
    public static final String COMMENT = "COMMUNITY_COMMENT";
    private static final String HIDDEN = CommunityStatus.TEMPORARILY_HIDDEN.name();
    /** Độ dài cột community_report.resolution_note. */
    static final int MAX_NOTE = 500;

    private final CommunityAccessService access;
    private final CommunityContentRepository contents;
    private final CommunityCommentRepository comments;
    private final CommunityReportRepository reports;

    @Override
    public boolean supports(String targetType) {
        return CONTENT.equals(targetType) || COMMENT.equals(targetType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReportedTarget> hiddenTargets() {
        return Stream.concat(
                contents.findByStatus(HIDDEN).stream().map(this::toTarget),
                comments.findByStatusAndDeletedAtIsNull(HIDDEN).stream().map(this::toTarget)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReportedTarget> find(String targetType, Long targetId) {
        if (CONTENT.equals(targetType)) return contents.findById(targetId).map(this::toTarget);
        if (COMMENT.equals(targetType)) {
            return comments.findById(targetId).filter(c -> c.getDeletedAt() == null).map(this::toTarget);
        }
        return Optional.empty();
    }

    /**
     * Khoá rồi đọc lại bản mới nhất ({@link CommunityAccessService#fresh}) trước khi đổi trạng thái: đối tượng đã bị gỡ
     * (vd kiểm duyệt viên khác vừa gỡ trực tiếp trong lúc người này xử lý hàng chờ) thì 409, không hồi sinh / gỡ lại.
     */
    @Override
    @Transactional
    public void resolve(String targetType, Long targetId, ReportDecision decision) {
        if (CONTENT.equals(targetType)) {
            CommunityContent row = access.fresh(contents.lockById(targetId).orElseThrow(CommunityAccessService::notFound));
            requireNotRemoved(row.getStatus());
            row.setStatus(decision.newState());
            contents.save(row);
            closeReports(ReportTargetType.CONTENT, targetId, decision);
            return;
        }
        CommunityComment existing = comments.findById(targetId).orElseThrow(CommunityAccessService::notFound);
        CommunityContent row = access.fresh(contents.lockById(existing.getCommunityContentId())
                .orElseThrow(CommunityAccessService::notFound));
        CommunityComment comment = access.fresh(comments.lockById(targetId).orElseThrow(CommunityAccessService::notFound));
        requireNotRemoved(comment.getStatus());
        comment.setStatus(decision.newState());
        comments.saveAndFlush(comment);
        closeReports(ReportTargetType.COMMENT, targetId, decision);
        access.refreshCommentCount(row);
    }

    private static void requireNotRemoved(String status) {
        if (CommunityStatus.REMOVED.name().equals(status)) {
            throw new ApiException(ErrorCode.CONFLICT, "Nội dung đã bị gỡ");
        }
    }

    private void closeReports(ReportTargetType type, Long targetId, ReportDecision decision) {
        String status = decision.valid() ? ReportStatus.RESOLVED.name() : ReportStatus.REJECTED.name();
        for (CommunityReport report : reports.lockOpen(type.name(), targetId)) {
            report.setStatus(status);
            report.setResolvedBy(decision.moderatorId());
            report.setResolvedAt(decision.decidedAt());
            report.setResolutionNote(note(decision.reason()));
        }
    }

    /**
     * community_report.resolution_note là VARCHAR(500) còn hàng chờ cho lý do tới 1000 ký tự (lý do đầy đủ vẫn nằm
     * trong moderation_audit_log.reason): cắt cho vừa thay vì để MySQL báo lỗi 1406 làm hỏng cả lần xử lý.
     */
    static String note(String reason) {
        return reason == null ? null : CommunityPostHandler.cut(reason, MAX_NOTE - 1);
    }

    private ReportedTarget toTarget(CommunityContent row) {
        CommunitySource source = access.handler(row.getContentType())
                .flatMap(h -> h.resolve(row.getContentId())).orElse(null);
        List<CommunityReport> open = openReports(ReportTargetType.CONTENT, row.getId());
        Long ownerId = source == null ? null : source.ownerId();
        String title = source == null ? "(nội dung gốc không còn)" : source.title();
        return new ReportedTarget(CONTENT, row.getId(), row.getStatus(), ownerId, ownerName(ownerId),
                CommunityContentType.label(row.getContentType()) + ": " + title,
                pageUrl(row), open.size(), reasons(open), hiddenAt(open, row.getUpdatedAt()));
    }

    private ReportedTarget toTarget(CommunityComment comment) {
        CommunityContent row = contents.findById(comment.getCommunityContentId()).orElse(null);
        String on = row == null ? "" : access.handler(row.getContentType())
                .flatMap(h -> h.resolve(row.getContentId()))
                .map(s -> "\n(Bình luận trên: " + s.title() + ")").orElse("");
        List<CommunityReport> open = openReports(ReportTargetType.COMMENT, comment.getId());
        return new ReportedTarget(COMMENT, comment.getId(), comment.getStatus(), comment.getUserId(),
                ownerName(comment.getUserId()), comment.getBody() + on, row == null ? null : pageUrl(row),
                open.size(), reasons(open), hiddenAt(open, comment.getUpdatedAt()));
    }

    private List<CommunityReport> openReports(ReportTargetType type, Long targetId) {
        return reports.findByTargetTypeAndTargetIdAndStatusOrderByCreatedAtAsc(type.name(), targetId,
                ReportStatus.OPEN.name());
    }

    private String ownerName(Long ownerId) {
        return ownerId == null ? null : access.author(ownerId).displayName();
    }

    private static String pageUrl(CommunityContent row) {
        return CommunityContentType.pageUrl(row.getContentType(), row.getContentId());
    }

    private static List<String> reasons(List<CommunityReport> open) {
        List<String> result = new ArrayList<>();
        for (CommunityReport report : open) {
            String label = ReportReason.labelOf(report.getReason());
            result.add(report.getNote() == null || report.getNote().isBlank() ? label : label + ": " + report.getNote());
        }
        return result;
    }

    private static LocalDateTime hiddenAt(List<CommunityReport> open, LocalDateTime fallback) {
        return open.isEmpty() ? fallback : open.get(open.size() - 1).getCreatedAt();
    }
}
