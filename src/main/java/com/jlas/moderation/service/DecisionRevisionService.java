package com.jlas.moderation.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import com.jlas.moderation.dto.ModerationTargetSnapshot;
import com.jlas.moderation.entity.ModerationAuditLog;
import com.jlas.moderation.entity.ReviewCase;
import com.jlas.moderation.entity.UserSanction;
import com.jlas.moderation.repository.ReviewCaseRepository;
import com.jlas.moderation.repository.UserSanctionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes a decision that was already made on a review case (AI queue item, dictionary contribution,
 * appeal), started from its row in the audit trail. A revision flips the case to the opposite outcome,
 * applies the matching side effects, writes a new *_REVISE audit row and tells the affected user.
 * The original audit row is never edited, so the trail shows every decision in order.
 */
@Service
@RequiredArgsConstructor
public class DecisionRevisionService {
    private static final String WORD_CONTRIBUTION = ModerationAdminService.WORD_CONTRIBUTION;

    private final ModerationAuditService audit;
    private final ReviewCaseRepository reviewCases;
    private final UserRepository users;
    private final List<ModerationTargetHandler> targetHandlers;
    private final UserCreditService credits;
    private final UserSanctionRepository sanctions;
    private final UserSanctionService sanctionService;
    private final ModerationNotifier notifier;

    public record Revision(Long auditId, Long caseId, String fromState, String toState) {
    }

    @Transactional
    public Revision revise(Long auditId, Long actorId, boolean actorIsAdmin, String reason) {
        String why = ModerationAuditService.requireReason(reason);
        ModerationAuditLog decision = audit.requireRevisable(auditId, actorId, actorIsAdmin);
        ReviewCase item = reviewCases.findForDecisionUpdate(decision.getCaseId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy mục đã xử lý"));
        String from = item.getStatus();
        if (!"APPROVED".equals(from) && !"REJECTED".equals(from)) {
            throw new ApiException(ErrorCode.CONFLICT, "Mục này chưa có quyết định để đổi");
        }
        boolean approve = "REJECTED".equals(from);
        User actor = users.findById(actorId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người thao tác"));

        Long revisionId = "APPEAL".equals(item.getCaseType())
                ? reviseAppeal(item, approve, why, actorId)
                : reviseContent(item, approve, why, actorId);

        item.setReviewer(actor);
        item.setStatus(approve ? "APPROVED" : "REJECTED");
        item.setResolutionNote(why);
        item.setResolvedAt(LocalDateTime.now());
        reviewCases.save(item);
        return new Revision(revisionId, item.getId(), from, item.getStatus());
    }

    private Long reviseContent(ReviewCase item, boolean approve, String why, Long actorId) {
        if (!"CONTENT_REVIEW".equals(item.getCaseType())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Loại mục này chưa hỗ trợ đổi quyết định");
        }
        ModerationTargetHandler handler = targetHandlers.stream()
                .filter(value -> value.supports(item.getTargetType())).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST,
                        "Chưa hỗ trợ loại nội dung " + item.getTargetType()));
        ModerationTargetSnapshot snapshot = handler.snapshot(item.getTargetType(), item.getTargetId());
        handler.revise(item.getTargetType(), item.getTargetId(), approve, why);
        item.setResolutionType(approve ? "ALLOW" : "BLOCK");

        boolean dictionary = WORD_CONTRIBUTION.equals(item.getTargetType());
        Long ownerId = item.getOwnerUser() == null ? null : item.getOwnerUser().getId();
        Long revisionId = audit.record(actorId, new ModerationAuditService.Entry(
                dictionary ? "DICTIONARY_REVISE" : "QUEUE_REVISE", item.getTargetType(), item.getTargetId(),
                item.getStatus(), approve ? "APPROVED" : "REJECTED", why, item.getId()));
        int creditChange = 0;
        if (dictionary && ownerId != null) {
            // Net credit for this word ends at the reward when published and at 0 when not.
            creditChange = credits.settle(ownerId, approve ? ModerationAdminService.WORD_CONTRIBUTION_REWARD : 0,
                    CreditReason.WORD_CONTRIBUTION_REWARD, CreditReason.WORD_CONTRIBUTION_REVOKED,
                    WORD_CONTRIBUTION, item.getTargetId(), revisionId,
                    approve ? "Đóng góp từ vựng được duyệt lại" : "Thu hồi thưởng: đóng góp bị gỡ khỏi từ điển");
        }
        if (dictionary) {
            notifier.contributionRevised(ownerId, ModerationAdminService.word(snapshot.contentPreview()), approve,
                    creditChange, why, revisionId);
        } else {
            notifier.queueRevised(ownerId, item.getTargetType(), approve, why, "USER_AVATAR".equals(item.getTargetType()) ? "/profile" : snapshot.targetUrl(), revisionId);
        }
        return revisionId;
    }

    private Long reviseAppeal(ReviewCase appeal, boolean approve, String why, Long actorId) {
        UserSanction sanction = sanctions.findById(appeal.getTargetId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy chế tài của đơn"));
        boolean reinstated = false;
        if (approve) {
            if (sanction.getRevokedAt() == null) {
                sanctionService.revoke(sanction.getId(), actorId, why, appeal.getId());
            }
        } else {
            reinstated = sanctionService.reinstate(sanction.getId(), actorId, why, appeal.getId());
        }
        appeal.setResolutionType(approve ? "REVOKE_SANCTION" : "KEEP_SANCTION");
        Long revisionId = audit.record(actorId, new ModerationAuditService.Entry("APPEAL_REVISE", "APPEAL",
                appeal.getId(), appeal.getStatus(), approve ? "APPROVED" : "REJECTED", why, appeal.getId()));
        notifier.appealRevised(appeal.getRequesterUser() == null ? null : appeal.getRequesterUser().getId(),
                appeal.getId(), approve, reinstated, sanction.getEndsAt(), why, revisionId);
        return revisionId;
    }
}
