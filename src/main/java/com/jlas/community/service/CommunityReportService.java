package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.ReportRequest;
import com.jlas.community.dto.CommunityDtos.ReportResult;
import com.jlas.community.entity.CommunityComment;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.entity.CommunityReport;
import com.jlas.community.enums.CommunityStatus;
import com.jlas.community.enums.ReportStatus;
import com.jlas.community.enums.ReportTargetType;
import com.jlas.community.handler.SourceReportSupport;
import com.jlas.community.repository.CommunityCommentRepository;
import com.jlas.community.repository.CommunityContentRepository;
import com.jlas.community.repository.CommunityReportRepository;
import com.jlas.community.service.CommunityAccessService.Target;
import com.jlas.moderation.service.UserActionPolicyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;

/**
 * Báo cáo nội dung/bình luận. Mỗi người báo cáo một đối tượng một lần (trùng → 409), không tự báo cáo.
 * Khi số báo cáo OPEN đạt {@code community.report.auto-hide-threshold} thì đối tượng chuyển
 * TEMPORARILY_HIDDEN ngay trong cùng transaction; moderator xử lý tiếp ở /admin/moderation/reports.
 * Báo cáo NỘI DUNG của loại có {@link SourceReportSupport} (video Shadowing) đi thẳng sang luồng của nguồn.
 */
@Service
@RequiredArgsConstructor
public class CommunityReportService {
    static final String DUPLICATE_MESSAGE = "Bạn đã báo cáo nội dung này";

    private final CommunityAccessService access;
    private final CommunityContentRepository contents;
    private final CommunityCommentRepository comments;
    private final CommunityReportRepository reports;
    private final UserActionPolicyPort actionPolicy;
    private final CommunityNotifier notifier;

    @Value("${community.report.auto-hide-threshold:1}")
    private int autoHideThreshold;

    @Transactional
    public ReportResult report(ReportRequest request, Long viewerId) {
        actionPolicy.assertCanAct(viewerId);
        return request.targetType() == ReportTargetType.CONTENT
                ? reportContent(request, viewerId) : reportComment(request, viewerId);
    }

    private ReportResult reportContent(ReportRequest request, Long viewerId) {
        if (!StringUtils.hasText(request.contentType()) || request.contentId() == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Thiếu loại hoặc mã nội dung cần báo cáo");
        }
        Target target = access.requireViewable(request.contentType().trim(), request.contentId(), viewerId);
        if (target.ownedBy(viewerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không thể báo cáo nội dung của chính mình");
        }
        Optional<SourceReportSupport> sourceReports = access.handler(target.source().contentType())
                .filter(SourceReportSupport.class::isInstance).map(SourceReportSupport.class::cast);
        if (sourceReports.isPresent()) {
            // Nguồn có luồng báo cáo riêng (vd. video Shadowing, ngưỡng 3): không tạo community_report để mỗi
            // nội dung chỉ có một mục trong hàng chờ admin. Báo trùng / quá hạn mức do nguồn trả lỗi.
            if (!target.publicNow()) {
                throw new ApiException(ErrorCode.CONFLICT, "Nội dung đang không công khai nên không thể báo cáo");
            }
            String note = StringUtils.hasText(request.note()) ? request.note().trim() : null;
            boolean hidden = sourceReports.get().report(target.source().contentId(), request.reason(), note);
            return new ReportResult(null, hidden, sourceReports.get().reportedMessage());
        }
        CommunityContent row = access.lockInteractable(target).row();
        CommunityReport saved = save(request, viewerId, ReportTargetType.CONTENT, row.getId());
        row.setReportCount(row.getReportCount() + 1);
        boolean hidden = reachedThreshold(ReportTargetType.CONTENT, row.getId());
        if (hidden) row.setStatus(CommunityStatus.TEMPORARILY_HIDDEN.name());
        contents.save(row);
        return new ReportResult(saved.getId(), hidden, null);
    }

    private ReportResult reportComment(ReportRequest request, Long viewerId) {
        if (request.targetId() == null) throw new ApiException(ErrorCode.BAD_REQUEST, "Thiếu mã bình luận cần báo cáo");
        CommunityComment existing = comments.findById(request.targetId())
                .filter(c -> c.getDeletedAt() == null).orElseThrow(CommunityAccessService::notFound);
        // Khoá nội dung trước rồi tới bình luận (cùng thứ tự với xoá bình luận).
        CommunityContent row = contents.lockById(existing.getCommunityContentId())
                .orElseThrow(CommunityAccessService::notFound);
        Target target = access.requireViewable(row, viewerId);
        CommunityComment comment = comments.lockById(existing.getId())
                .filter(c -> c.getDeletedAt() == null
                        && CommunityStatus.PUBLISHED.name().equals(c.getStatus()))
                .orElseThrow(CommunityAccessService::notFound);
        if (Objects.equals(comment.getUserId(), viewerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không thể báo cáo bình luận của chính mình");
        }
        if (!target.publicNow()) {
            throw new ApiException(ErrorCode.CONFLICT, "Nội dung đang không công khai nên không thể báo cáo");
        }
        CommunityReport saved = save(request, viewerId, ReportTargetType.COMMENT, comment.getId());
        comment.setReportCount(comment.getReportCount() + 1);
        boolean hidden = reachedThreshold(ReportTargetType.COMMENT, comment.getId());
        if (hidden) comment.setStatus(CommunityStatus.TEMPORARILY_HIDDEN.name());
        comments.saveAndFlush(comment);
        if (hidden) access.refreshCommentCount(row);
        notifier.commentReported(comment.getUserId(), target.source(), saved.getId(), hidden);
        return new ReportResult(saved.getId(), hidden, null);
    }

    private CommunityReport save(ReportRequest request, Long viewerId, ReportTargetType type, Long targetId) {
        if (reports.existsByReporterIdAndTargetTypeAndTargetId(viewerId, type.name(), targetId)) {
            throw new ApiException(ErrorCode.CONFLICT, DUPLICATE_MESSAGE);
        }
        String note = StringUtils.hasText(request.note()) ? request.note().trim() : null;
        try {
            return reports.saveAndFlush(CommunityReport.builder().reporterId(viewerId).targetType(type.name())
                    .targetId(targetId).reason(request.reason().name()).note(note)
                    .status(ReportStatus.OPEN.name()).build());
        } catch (DataIntegrityViolationException e) {
            // Hai request báo cáo cùng lúc: UNIQUE(reporter_id, target_type, target_id) chặn bản thứ hai.
            throw new ApiException(ErrorCode.CONFLICT, DUPLICATE_MESSAGE);
        }
    }

    /** So với số báo cáo OPEN (không tính báo cáo đã xử lý) để nội dung đã khôi phục không bị ẩn lại ngay. */
    private boolean reachedThreshold(ReportTargetType type, Long targetId) {
        long open = reports.countByTargetTypeAndTargetIdAndStatus(type.name(), targetId, ReportStatus.OPEN.name());
        return open >= Math.max(1, autoHideThreshold);
    }
}
