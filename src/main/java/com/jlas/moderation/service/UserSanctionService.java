package com.jlas.moderation.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.entity.UserSanction;
import com.jlas.moderation.repository.UserSanctionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserSanctionService implements UserActionPolicyPort {
    private static final List<Integer> ALLOWED_MUTE_DAYS = List.of(3, 7, 30);

    private final UserSanctionRepository sanctions;
    private final UserRepository users;
    private final ModerationAuditService audit;
    private final ModerationNotifier notifier;
    private final SanctionStatusService status;

    public enum SanctionType { MUTE, BAN }

    public record SanctionView(Long id, Long userId, String type, String reason,
                               LocalDateTime startsAt, LocalDateTime endsAt,
                               LocalDateTime revokedAt) {
    }

    @Transactional
    public SanctionView apply(Long userId, Long actorId, SanctionType type,
                              Integer durationDays, String reason) {
        if (userId.equals(actorId)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không thể tự áp dụng chế tài");
        }
        String why = ModerationAuditService.requireReason(reason);
        int days = duration(type, durationDays);
        User user = requiredUser(userId);
        User actor = requiredUser(actorId);
        LocalDateTime now = LocalDateTime.now();

        sanctions.findActiveByUserIdAndType(userId, type.name(), now).ifPresent(existing -> {
            existing.setRevokedAt(now);
            audit.record(actorId, new ModerationAuditService.Entry("SANCTION_REPLACE", "USER_SANCTION",
                    existing.getId(), "ACTIVE", "REVOKED", why, null));
        });

        UserSanction sanction = UserSanction.builder()
                .user(user)
                .createdBy(actor)
                .sanctionType(type.name())
                .reason(why)
                .endsAt(type == SanctionType.BAN && durationDays == null ? null : now.plusDays(days))
                .build();
        UserSanction saved = sanctions.save(sanction);
        status.invalidate(userId);
        notifier.sanctionApplied(userId, saved.getId(), type.name(), saved.getEndsAt(), why);
        audit.record(actorId, new ModerationAuditService.Entry("SANCTION_APPLY", "USER", userId,
                null, type.name() + (saved.getEndsAt() == null ? ":PERMANENT" : ":" + days + "D"), why, null));
        return view(saved);
    }

    @Transactional
    public SanctionView revoke(Long sanctionId, Long actorId, String reason) {
        return revoke(sanctionId, actorId, reason, null);
    }

    /** Revokes an active sanction; {@code caseId} links the audit row to an appeal when there is one. */
    @Transactional
    public SanctionView revoke(Long sanctionId, Long actorId, String reason, Long caseId) {
        String why = ModerationAuditService.requireReason(reason);
        requiredUser(actorId);
        UserSanction sanction = sanctions.findById(sanctionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy chế tài"));
        if (sanction.getRevokedAt() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "Chế tài đã được gỡ trước đó");
        }
        sanction.setRevokedAt(LocalDateTime.now());
        status.invalidate(sanction.getUser().getId());
        audit.record(actorId, new ModerationAuditService.Entry("SANCTION_REVOKE", "USER_SANCTION",
                sanction.getId(), "ACTIVE", "REVOKED", why, caseId));
        // An approved appeal sends its own notification, so only direct revokes notify here.
        if (caseId == null) notifier.sanctionRevoked(sanction.getUser().getId(), sanction.getId());
        return view(sanctions.save(sanction));
    }

    /**
     * Undoes a revoke when an approved appeal is revised to rejected. Returns false when the sanction
     * already ran out: there is nothing left to put back.
     */
    @Transactional
    public boolean reinstate(Long sanctionId, Long actorId, String reason, Long caseId) {
        String why = ModerationAuditService.requireReason(reason);
        UserSanction sanction = sanctions.findById(sanctionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy chế tài"));
        if (sanction.getRevokedAt() == null) return true;
        if (sanction.getEndsAt() != null && !sanction.getEndsAt().isAfter(LocalDateTime.now())) return false;
        sanction.setRevokedAt(null);
        sanctions.save(sanction);
        status.invalidate(sanction.getUser().getId());
        audit.record(actorId, new ModerationAuditService.Entry("SANCTION_REINSTATE", "USER_SANCTION",
                sanction.getId(), "REVOKED", "ACTIVE", why, caseId));
        return true;
    }

    @Transactional(readOnly = true)
    public List<SanctionView> history(Long userId) {
        return sanctions.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<SanctionView> allActive() {
        return sanctions.findAllActive(LocalDateTime.now()).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<SanctionView> activeFor(Long userId) {
        return sanctions.findActiveByUserId(userId, LocalDateTime.now()).stream()
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    @Override
    public void assertCanAct(Long userId) {
        sanctions.findLatestActiveByUserId(userId, LocalDateTime.now()).ifPresent(sanction -> {
            String until = sanction.getEndsAt() == null ? "vô thời hạn" : "đến " + sanction.getEndsAt();
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Tài khoản đang bị " + sanction.getSanctionType().toLowerCase() + " " + until);
        });
    }

    @Transactional(readOnly = true)
    public UserSanction activeSanction(Long sanctionId, Long userId) {
        UserSanction sanction = sanctions.findById(sanctionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy chế tài"));
        boolean active = sanction.getRevokedAt() == null
                && (sanction.getEndsAt() == null || sanction.getEndsAt().isAfter(LocalDateTime.now()));
        if (!sanction.getUser().getId().equals(userId) || !active) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Chế tài không còn hiệu lực hoặc không thuộc tài khoản");
        }
        return sanction;
    }

    private int duration(SanctionType type, Integer days) {
        if (type == SanctionType.BAN && days == null) {
            return 0;
        }
        if (days == null || !ALLOWED_MUTE_DAYS.contains(days)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Thời hạn chỉ hỗ trợ 3, 7 hoặc 30 ngày");
        }
        return days;
    }

    private User requiredUser(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
    }

    private SanctionView view(UserSanction value) {
        return new SanctionView(value.getId(), value.getUser().getId(), value.getSanctionType(),
                value.getReason(), value.getStartsAt(), value.getEndsAt(), value.getRevokedAt());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
