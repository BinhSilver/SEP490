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
    public static final String WORD_CONTRIBUTION = "WORD_CONTRIBUTION";

    private final ReviewCaseRepository reviewCases;
    private final UserRepository users;
    private final UserCreditService credits;
    private final List<ModerationTargetHandler> targetHandlers;
    private final ModerationAuditService audit;
    private final ContentModerationService scanner;
    private final ModerationNotifier notifier;

    /** AI-flagged content only; dictionary contributions have their own page. */
    @Transactional(readOnly = true)
    public List<ModerationQueueItem> listContentQueue() {
        return reviewCases.findOpenModerationQueue().stream()
                .filter(item -> !WORD_CONTRIBUTION.equals(item.getTargetType()))
                .map(this::toQueueItem).toList();
    }

    @Transactional(readOnly = true)
    public List<ModerationQueueItem> listDictionaryContributions() {
        return reviewCases.findOpenModerationQueue().stream()
                .filter(item -> WORD_CONTRIBUTION.equals(item.getTargetType()))
                .map(this::toQueueItem).toList();
    }

    @Transactional
    public ModerationResolution resolve(Long queueId, Long reviewerId, boolean approve, String note) {
        ReviewCase item = reviewCases.findForDecisionUpdate(queueId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy mục duyệt"));
        if (!"CONTENT_REVIEW".equals(item.getCaseType())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Case không thuộc hàng đợi nội dung");
        }
        if (!"OPEN".equals(item.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Mục đã được xử lý");
        }
        String why = ModerationAuditService.requireReason(note);

        ModerationTargetHandler targetHandler = handler(item.getTargetType())
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST,
                        "Chưa hỗ trợ loại nội dung " + item.getTargetType()));
        // Read the target before resolving: it may be hidden afterwards.
        ModerationTargetSnapshot snapshot = targetHandler.snapshot(item.getTargetType(), item.getTargetId());
        targetHandler.resolve(item.getTargetType(), item.getTargetId(), approve, why);

        User reviewer = users.findById(reviewerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy reviewer"));
        item.setReviewer(reviewer);
        item.setStatus(approve ? "APPROVED" : "REJECTED");
        item.setResolutionType(approve ? "ALLOW" : "BLOCK");
        item.setResolutionNote(why);
        item.setResolvedAt(LocalDateTime.now());
        reviewCases.save(item);
        boolean dictionary = WORD_CONTRIBUTION.equals(item.getTargetType());
        audit.record(reviewerId, new ModerationAuditService.Entry(
                (dictionary ? "DICTIONARY_" : "QUEUE_") + (approve ? "APPROVE" : "REJECT"),
                item.getTargetType(), item.getTargetId(), "OPEN", item.getStatus(), why, item.getId()));

        Integer creditReward = null;
        Long creditedTo = null;
        if (approve && dictionary && item.getOwnerUser() != null) {
            creditedTo = item.getOwnerUser().getId();
            credits.earn(creditedTo, WORD_CONTRIBUTION_REWARD,
                    CreditReason.WORD_CONTRIBUTION_REWARD,
                    WORD_CONTRIBUTION, item.getTargetId(),
                    "Đóng góp từ vựng đã được duyệt");
            creditReward = WORD_CONTRIBUTION_REWARD;
        }

        Long ownerId = item.getOwnerUser() == null ? null : item.getOwnerUser().getId();
        if (dictionary) {
            notifier.contributionDecision(ownerId, word(snapshot.contentPreview()), approve,
                    WORD_CONTRIBUTION_REWARD, why, item.getId());
        } else {
            notifier.queueDecision(ownerId, item.getTargetType(), item.getTargetId(), approve, why,
                    "USER_AVATAR".equals(item.getTargetType()) ? "/profile" : snapshot.targetUrl(), item.getId());
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
                log == null ? null : log.getSource(), target.targetUrl(), item.getCreatedAt(),
                scanner.highlight(target.contentPreview()));
    }

    /** Contribution previews look like "推し 【おし】 meaning"; keep the headword for the message. */
    static String word(String preview) {
        if (preview == null || preview.isBlank()) return null;
        int bracket = preview.indexOf('【');
        return (bracket > 0 ? preview.substring(0, bracket) : preview).trim();
    }

    private java.util.Optional<ModerationTargetHandler> handler(String targetType) {
        return targetHandlers.stream().filter(value -> value.supports(targetType)).findFirst();
    }

}
