package com.jlas.moderation.service;

import com.jlas.auth.security.CurrentUser;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.entity.ModerationAuditLog;
import com.jlas.moderation.repository.ModerationAuditLogRepository;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single entry point for the moderation audit trail.
 * {@link Propagation#MANDATORY} forces callers to write the audit row inside the same
 * transaction as the state change: if either fails, both roll back.
 */
@Service
@RequiredArgsConstructor
public class ModerationAuditService {
    public static final int MAX_REASON = 1000;

    /** Decisions on a review case that can be revised later from the audit trail. */
    public static final Set<String> DECISION_ACTIONS = Set.of(
            "QUEUE_APPROVE", "QUEUE_REJECT", "QUEUE_REVISE",
            "DICTIONARY_APPROVE", "DICTIONARY_REJECT", "DICTIONARY_REVISE",
            "APPEAL_APPROVE", "APPEAL_REJECT", "APPEAL_REVISE");

    private final ModerationAuditLogRepository audits;
    private final AuditAffectedUsers affectedUsers;

    /** One audited transition. {@code reason} is required by the ticket. */
    public record Entry(String action, String targetType, Long targetId,
                        String fromState, String toState, String reason, Long caseId) {
    }

    public record AuditView(Long id, Long actorUserId, String actorRole, String action,
                            String targetType, Long targetId, String fromState, String toState,
                            String reason, Long caseId, java.time.LocalDateTime createdAt,
                            boolean revisable, AuditAffectedUsers.Person affected) {
    }

    public record AuditPage(java.util.List<AuditView> items, int page, int size,
                            long totalElements, int totalPages) {
    }

    /**
     * Audit row for an action the system takes on its own (no actor), e.g. an automatic ban for bulk downloading.
     * The request still runs as the offending user, so the role is fixed to SYSTEM instead of read from the request.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long recordSystem(Entry entry) {
        return audits.save(ModerationAuditLog.builder()
                .actorUserId(null)
                .actorRole("SYSTEM")
                .action(entry.action())
                .targetType(entry.targetType())
                .targetId(entry.targetId())
                .fromState(entry.fromState())
                .toState(entry.toState())
                .reason(requireReason(entry.reason()))
                .caseId(entry.caseId())
                .build()).getId();
    }

    /** Writes one audit row and returns its id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long record(Long actorUserId, Entry entry) {
        return audits.save(ModerationAuditLog.builder()
                .actorUserId(actorUserId)
                .actorRole(currentRole())
                .action(entry.action())
                .targetType(entry.targetType())
                .targetId(entry.targetId())
                .fromState(entry.fromState())
                .toState(entry.toState())
                .reason(requireReason(entry.reason()))
                .caseId(entry.caseId())
                .build()).getId();
    }

    /**
     * {@code viewerId}/{@code viewerIsAdmin} decide which rows get a "change decision" action:
     * the decision currently in force on its case, made by the viewer, or any such row for an admin.
     */
    @Transactional(readOnly = true)
    public AuditPage search(Long actorId, String action, String targetType, Long targetId,
                            LocalDate from, LocalDate to, int page, int size,
                            Long viewerId, boolean viewerIsAdmin) {
        Page<ModerationAuditLog> result = audits.search(actorId, blankToNull(action), blankToNull(targetType),
                targetId, from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay(),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
        java.util.Map<Long, AuditAffectedUsers.Person> affected = affectedUsers.forRows(result.getContent());
        return new AuditPage(result.getContent().stream()
                .map(row -> view(row, isCurrentDecision(row) && mayRevise(row, viewerId, viewerIsAdmin),
                        affected.get(row.getId()))).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /**
     * Loads the audit row a revision starts from and checks it is still the decision in force
     * and that the viewer may change it.
     */
    @Transactional(readOnly = true)
    public ModerationAuditLog requireRevisable(Long auditId, Long viewerId, boolean viewerIsAdmin) {
        ModerationAuditLog row = audits.findById(auditId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy bản ghi nhật ký"));
        if (!DECISION_ACTIONS.contains(row.getAction()) || row.getCaseId() == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Thao tác này không đổi được từ nhật ký");
        }
        if (!isCurrentDecision(row)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Quyết định này đã được thay bằng một quyết định mới hơn. Hãy tải lại nhật ký.");
        }
        if (!mayRevise(row, viewerId, viewerIsAdmin)) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Chỉ người ra quyết định hoặc quản trị viên mới được đổi quyết định này");
        }
        return row;
    }

    private boolean isCurrentDecision(ModerationAuditLog row) {
        if (row.getCaseId() == null || !DECISION_ACTIONS.contains(row.getAction())) return false;
        return audits.findFirstByCaseIdAndActionInOrderByIdDesc(row.getCaseId(), DECISION_ACTIONS)
                .map(latest -> latest.getId().equals(row.getId()))
                .orElse(false);
    }

    private static boolean mayRevise(ModerationAuditLog row, Long viewerId, boolean viewerIsAdmin) {
        return viewerIsAdmin || (viewerId != null && viewerId.equals(row.getActorUserId()));
    }

    /** Validates a moderator reason; shared so every decision enforces the same rule. */
    public static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Vui lòng nhập lý do");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Lý do tối đa " + MAX_REASON + " ký tự");
        }
        return trimmed;
    }

    private static String currentRole() {
        String roles = CurrentUser.current()
                .map(user -> user.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(value -> value.startsWith("ROLE_") ? value.substring(5) : value)
                        .collect(Collectors.joining(",")))
                .orElse("");
        return roles.isBlank() ? "SYSTEM" : roles;
    }

    private static AuditView view(ModerationAuditLog a, boolean revisable, AuditAffectedUsers.Person affected) {
        return new AuditView(a.getId(), a.getActorUserId(), a.getActorRole(), a.getAction(),
                a.getTargetType(), a.getTargetId(), a.getFromState(), a.getToState(),
                a.getReason(), a.getCaseId(), a.getCreatedAt(), revisable, affected);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }
}
