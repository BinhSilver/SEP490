package com.jlas.moderation.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.moderation.dto.ModerationQueueItem;
import com.jlas.moderation.dto.ModerationResolution;
import com.jlas.moderation.dto.ModerationTargetSnapshot;
import com.jlas.moderation.entity.ContentModerationLog;
import com.jlas.moderation.entity.ReviewCase;
import com.jlas.moderation.repository.ReviewCaseRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ModerationAdminService {
    public static final int WORD_CONTRIBUTION_REWARD = 15;

    private final ReviewCaseRepository reviewCases;
    private final UserRepository users;
    private final UserCreditService credits;
    private final List<ModerationTargetHandler> targetHandlers;

    @Transactional(readOnly = true)
    public List<ModerationQueueItem> listOpen() {
        return reviewCases.findOpenModerationQueue().stream().map(this::toQueueItem).toList();
    }

    @Transactional
    public ModerationResolution resolve(Long queueId, Long reviewerId, boolean approve, String note) {
        ReviewCase item = reviewCases.findById(queueId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy mục duyệt"));
        if (!"CONTENT_REVIEW".equals(item.getCaseType())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Case không thuộc hàng đợi nội dung");
        }
        if (!"OPEN".equals(item.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Mục đã được xử lý");
        }

        handler(item.getTargetType()).ifPresentOrElse(
                value -> value.resolve(item.getTargetType(), item.getTargetId(), approve, note),
                () -> {
                    if (!"WORD_CONTRIBUTION".equals(item.getTargetType())) {
                        throw new ApiException(ErrorCode.BAD_REQUEST,
                                "Chưa hỗ trợ loại nội dung " + item.getTargetType());
                    }
                });

        User reviewer = users.findById(reviewerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy reviewer"));
        item.setReviewer(reviewer);
        item.setStatus(approve ? "APPROVED" : "REJECTED");
        item.setResolutionType(approve ? "ALLOW" : "BLOCK");
        item.setResolutionNote(trimToNull(note));
        item.setResolvedAt(LocalDateTime.now());
        reviewCases.save(item);

        Integer creditReward = null;
        Long creditedTo = null;
        if (approve && "WORD_CONTRIBUTION".equals(item.getTargetType()) && item.getOwnerUser() != null) {
            creditedTo = item.getOwnerUser().getId();
            credits.earn(creditedTo, WORD_CONTRIBUTION_REWARD,
                    CreditReason.WORD_CONTRIBUTION_REWARD,
                    "WORD_CONTRIBUTION", item.getTargetId(),
                    "Đóng góp từ vựng đã được duyệt");
            creditReward = WORD_CONTRIBUTION_REWARD;
        }

        return new ModerationResolution(item.getId(), item.getStatus(), item.getTargetType(),
                item.getTargetId(), creditReward, creditedTo);
    }

    private ModerationQueueItem toQueueItem(ReviewCase item) {
        ContentModerationLog log = item.getModerationLog();
        ModerationTargetSnapshot target = handler(item.getTargetType())
                .map(value -> value.snapshot(item.getTargetType(), item.getTargetId()))
                .orElseGet(ModerationTargetSnapshot::empty);
        return new ModerationQueueItem(
                item.getId(), item.getTargetType(), item.getTargetId(),
                item.getOwnerUser() == null ? null : item.getOwnerUser().getId(),
                item.getTitleSnapshot(), item.getReason(), item.getStatus(),
                target.contentPreview(), log == null ? null : log.getCategoriesJson(),
                log == null || log.getConfidence() == null ? null : log.getConfidence().doubleValue(),
                log == null ? null : log.getModelName(),
                log == null ? null : log.getSource(), target.targetUrl(), item.getCreatedAt());
    }

    private java.util.Optional<ModerationTargetHandler> handler(String targetType) {
        return targetHandlers.stream().filter(value -> value.supports(targetType)).findFirst();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
