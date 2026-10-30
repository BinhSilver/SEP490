package com.jlas.moderation.report;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.service.ModerationAuditService;
import com.jlas.moderation.service.ModerationNotifier;
import com.jlas.moderation.service.UserSanctionService;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves reported community content: valid report -> REMOVED, invalid -> PUBLISHED.
 * Only targets already TEMPORARILY_HIDDEN are accepted. Works with zero ports until JLAS2-37 lands.
 */
@Service
@RequiredArgsConstructor
public class ReportReviewService {
    private final ObjectProvider<ReportedContentPort> ports;
    private final ModerationAuditService audit;
    private final UserSanctionService sanctions;
    private final ModerationNotifier notifier;

    /** Optional sanction applied to the content owner together with a valid report. */
    public record SanctionRequest(UserSanctionService.SanctionType type, Integer durationDays) {
    }

    public record ReportResolution(String targetType, Long targetId, String fromState, String toState,
                                   Long sanctionId) {
    }

    public boolean available() {
        return ports.orderedStream().findAny().isPresent();
    }

    @Transactional(readOnly = true)
    public List<ReportedTarget> pending() {
        return ports.orderedStream()
                .flatMap(port -> port.hiddenTargets().stream())
                .filter(target -> ContentState.TEMPORARILY_HIDDEN.equals(target.state()))
                .sorted(Comparator.comparing(ReportedTarget::reportCount).reversed()
                        .thenComparing(ReportedTarget::hiddenAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Transactional
    public ReportResolution resolve(String targetType, Long targetId, boolean valid, String reason,
                                    SanctionRequest sanction, Long moderatorId, boolean canSanction) {
        String why = ModerationAuditService.requireReason(reason);
        ReportedContentPort port = ports.orderedStream().filter(p -> p.supports(targetType)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST,
                        "Loại nội dung " + targetType + " chưa hỗ trợ xử lý báo cáo"));
        ReportedTarget target = port.find(targetType, targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy nội dung bị báo cáo"));
        if (!ContentState.TEMPORARILY_HIDDEN.equals(target.state())) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Nội dung đang ở trạng thái " + target.state() + ", không thể xử lý báo cáo");
        }
        if (sanction != null && !valid) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Chỉ áp chế tài khi báo cáo đúng");
        }
        if (sanction != null && !canSanction) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không có quyền áp chế tài");
        }

        String toState = valid ? ContentState.REMOVED : ContentState.PUBLISHED;
        port.resolve(targetType, targetId, new ReportDecision(valid, toState, moderatorId, why, LocalDateTime.now()));
        audit.record(moderatorId, new ModerationAuditService.Entry(valid ? "REPORT_VALID" : "REPORT_INVALID",
                targetType, targetId, ContentState.TEMPORARILY_HIDDEN, toState, why, null));
        if (valid) notifier.contentRemoved(target.ownerUserId(), targetType, targetId, why);
        else notifier.contentRestored(target.ownerUserId(), targetType, targetId, target.targetUrl());

        Long sanctionId = null;
        if (sanction != null) {
            if (target.ownerUserId() == null) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Nội dung không có người đăng để áp chế tài");
            }
            sanctionId = sanctions.apply(target.ownerUserId(), moderatorId, sanction.type(),
                    sanction.durationDays(), why).id();
        }
        return new ReportResolution(targetType, targetId, ContentState.TEMPORARILY_HIDDEN, toState, sanctionId);
    }
}
