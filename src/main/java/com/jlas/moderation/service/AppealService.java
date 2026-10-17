package com.jlas.moderation.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.dto.AppealView;
import com.jlas.moderation.entity.ReviewCase;
import com.jlas.moderation.entity.UserSanction;
import com.jlas.moderation.repository.ReviewCaseRepository;
import com.jlas.moderation.repository.UserSanctionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AppealService {
    private final ReviewCaseRepository reviewCases;
    private final UserSanctionRepository sanctions;
    private final UserRepository users;
    private final UserSanctionService sanctionService;
    private final ModerationAuditService audit;
    private final ModerationNotifier notifier;
    private final AppealAttachmentStorage attachments;

    @Transactional
    public AppealView submit(Long userId, Long sanctionId, String reason, String evidenceUrl) {
        return submit(userId, sanctionId, reason, evidenceUrl, null);
    }

    @Transactional
    public AppealView submit(Long userId, Long sanctionId, String reason, String evidenceUrl,
                             MultipartFile file) {
        if (reason == null || reason.trim().length() < 20 || reason.length() > 1000) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Lý do cần từ 20 đến 1000 ký tự");
        }
        String link = validateEvidenceUrl(evidenceUrl);
        User requester = users.findForProfileUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
        reviewCases.findFirstByCaseTypeAndRequesterUserIdAndStatusOrderByCreatedAtDesc(
                "APPEAL", userId, "OPEN").ifPresent(existing -> {
                    throw new ApiException(ErrorCode.CONFLICT,
                            "Bạn đã có đơn đang chờ xử lý (#" + existing.getId() + ")");
                });

        UserSanction sanction = sanctionService.activeSanction(sanctionId, userId);
        ReviewCase appeal = ReviewCase.builder()
                .caseType("APPEAL")
                .targetType("USER_SANCTION")
                .targetId(sanction.getId())
                .requesterUser(requester)
                .ownerUser(requester)
                .titleSnapshot(sanction.getSanctionType() + " sanction")
                .reason(reason.trim())
                .evidenceUrl(link)
                .status("OPEN")
                .build();
        if (file != null) {
            var stored = attachments.store(file);
            appeal.setEvidenceFileKey(stored.key());
            appeal.setEvidenceFileName(stored.name());
            appeal.setEvidenceFileType(stored.contentType());
            appeal.setEvidenceFileSize(stored.size());
        }
        return view(reviewCases.save(appeal));
    }

    @Transactional(readOnly = true)
    public List<AppealView> mine(Long userId) {
        return reviewCases.findByCaseTypeAndRequesterUserIdOrderByCreatedAtDesc("APPEAL", userId)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<AppealView> list(String status) {
        String normalized = status == null || status.isBlank() ? null : status.toUpperCase();
        return reviewCases.findAppeals(normalized).stream().map(this::view).toList();
    }

    @Transactional
    public AppealView resolve(Long appealId, Long reviewerId, boolean approve, String note) {
        ReviewCase appeal = reviewCases.findForDecisionUpdate(appealId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn kháng nghị"));
        if (!"APPEAL".equals(appeal.getCaseType()) || !"OPEN".equals(appeal.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Đơn đã được xử lý hoặc không hợp lệ");
        }
        String why = ModerationAuditService.requireReason(note);
        User reviewer = users.findById(reviewerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy reviewer"));
        if (approve) {
            UserSanction sanction = sanctions.findById(appeal.getTargetId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy chế tài"));
            if (sanction.getRevokedAt() == null) {
                sanctionService.revoke(sanction.getId(), reviewerId, why, appeal.getId());
            }
        }
        appeal.setReviewer(reviewer);
        appeal.setStatus(approve ? "APPROVED" : "REJECTED");
        appeal.setResolutionType(approve ? "REVOKE_SANCTION" : "KEEP_SANCTION");
        appeal.setResolutionNote(why);
        appeal.setResolvedAt(LocalDateTime.now());
        audit.record(reviewerId, new ModerationAuditService.Entry(approve ? "APPEAL_APPROVE" : "APPEAL_REJECT",
                "APPEAL", appeal.getId(), "OPEN", appeal.getStatus(), why, appeal.getId()));
        notifier.appealResolved(appeal.getRequesterUser() == null ? null : appeal.getRequesterUser().getId(),
                appeal.getId(), approve, why);
        return view(reviewCases.save(appeal));
    }

    private AppealView view(ReviewCase value) {
        String sanctionType = value.getTitleSnapshot();
        if (sanctionType != null && sanctionType.endsWith(" sanction")) {
            sanctionType = sanctionType.substring(0, sanctionType.length() - " sanction".length());
        }
        return new AppealView(value.getId(),
                value.getRequesterUser() == null ? null : value.getRequesterUser().getId(),
                value.getTargetId(), sanctionType, value.getReason(), value.getEvidenceUrl(),
                value.getStatus(), value.getReviewer() == null ? null : value.getReviewer().getId(),
                value.getResolutionNote(), value.getCreatedAt(), value.getResolvedAt(),
                value.getEvidenceFileKey() == null ? null : new AppealView.EvidenceFile(
                        value.getEvidenceFileName(), value.getEvidenceFileType(), value.getEvidenceFileSize(),
                        "/api/appeals/" + value.getId() + "/file"));
    }

    @Transactional(readOnly = true)
    public ReviewCase attachment(Long appealId, Long userId, boolean reviewer) {
        ReviewCase value = reviewCases.findById(appealId)
                .filter(v -> "APPEAL".equals(v.getCaseType()) && v.getEvidenceFileKey() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy file đính kèm"));
        if (!reviewer && (value.getRequesterUser() == null || !userId.equals(value.getRequesterUser().getId()))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không có quyền xem file kháng nghị này");
        }
        return value;
    }

    private static String validateEvidenceUrl(String value) {
        String link = trimToNull(value);
        if (link == null) return null;
        try {
            var uri = java.net.URI.create(link);
            if (link.length() > 500 || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            return link;
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Liên kết bằng chứng phải là URL http:// hoặc https:// hợp lệ");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
