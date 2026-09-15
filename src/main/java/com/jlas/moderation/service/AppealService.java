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

@Service
@RequiredArgsConstructor
public class AppealService {
    private final ReviewCaseRepository reviewCases;
    private final UserSanctionRepository sanctions;
    private final UserRepository users;
    private final UserSanctionService sanctionService;

    @Transactional
    public AppealView submit(Long userId, Long sanctionId, String reason, String evidenceUrl) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Lý do không được để trống");
        }
        reviewCases.findFirstByCaseTypeAndRequesterUserIdAndStatusOrderByCreatedAtDesc(
                "APPEAL", userId, "OPEN").ifPresent(existing -> {
                    throw new ApiException(ErrorCode.CONFLICT,
                            "Bạn đã có đơn đang chờ xử lý (#" + existing.getId() + ")");
                });

        UserSanction sanction = sanctionService.activeSanction(sanctionId, userId);
        User requester = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
        ReviewCase appeal = ReviewCase.builder()
                .caseType("APPEAL")
                .targetType("USER_SANCTION")
                .targetId(sanction.getId())
                .requesterUser(requester)
                .ownerUser(requester)
                .titleSnapshot(sanction.getSanctionType() + " sanction")
                .reason(reason.trim())
                .evidenceUrl(trimToNull(evidenceUrl))
                .status("OPEN")
                .build();
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
        ReviewCase appeal = reviewCases.findById(appealId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn kháng nghị"));
        if (!"APPEAL".equals(appeal.getCaseType()) || !"OPEN".equals(appeal.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Đơn đã được xử lý hoặc không hợp lệ");
        }
        User reviewer = users.findById(reviewerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy reviewer"));
        if (approve) {
            UserSanction sanction = sanctions.findById(appeal.getTargetId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy chế tài"));
            sanction.setRevokedAt(LocalDateTime.now());
            sanctions.save(sanction);
        }
        appeal.setReviewer(reviewer);
        appeal.setStatus(approve ? "APPROVED" : "REJECTED");
        appeal.setResolutionType(approve ? "REVOKE_SANCTION" : "KEEP_SANCTION");
        appeal.setResolutionNote(trimToNull(note));
        appeal.setResolvedAt(LocalDateTime.now());
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
                value.getResolutionNote(), value.getCreatedAt(), value.getResolvedAt());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
