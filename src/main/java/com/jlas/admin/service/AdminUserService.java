package com.jlas.admin.service;

import com.jlas.admin.dto.AdminUserView;
import com.jlas.admin.repository.AdminUserRepository;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.AuthTokenRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {
    private final AdminUserRepository users;
    private final AuthTokenRepository tokens;
    private final AdminAuditService audit;

    public Page<AdminUserView> list(String q, String role, String status, Boolean premium, int page, int size) {
        if (status != null && !status.equals("ACTIVE") && !status.equals("LOCKED")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Trạng thái phải là ACTIVE hoặc LOCKED.");
        }
        LocalDateTime now = LocalDateTime.now();
        return users.findAll((root, query, cb) -> {
            var filters = new ArrayList<Predicate>();
            if (q != null && !q.isBlank()) {
                String term = "%" + q.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                filters.add(cb.or(cb.like(cb.lower(root.get("email")), term, '\\'),
                        cb.like(cb.lower(root.get("username")), term, '\\'),
                        cb.like(cb.lower(root.get("fullName")), term, '\\')));
            }
            if (role != null && !role.isBlank()) filters.add(cb.equal(root.get("role").get("name"), role));
            if (status != null) filters.add(status.equals("LOCKED")
                    ? cb.isNotNull(root.get("lockedAt")) : cb.isNull(root.get("lockedAt")));
            if (premium != null) filters.add(premium
                    ? cb.greaterThan(root.get("premiumExpiredAt"), now)
                    : cb.or(cb.isNull(root.get("premiumExpiredAt")), cb.lessThanOrEqualTo(root.get("premiumExpiredAt"), now)));
            return cb.and(filters.toArray(Predicate[]::new));
        }, pageRequest(page, size)).map(user -> AdminUserView.from(user, false, now));
    }

    public AdminUserView detail(Long id) {
        return AdminUserView.from(users.findById(id).orElseThrow(AdminUserService::notFound), true, LocalDateTime.now());
    }

    @Transactional
    public AdminUserView lock(Long actorId, Long id, String reason) {
        reason = validateReason(reason);
        // Reject before locking the actor row: the separate audit transaction references that row.
        if (actorId.equals(id)) reject(actorId, id, "USER_LOCK", reason, "Không thể tự khóa tài khoản.");
        users.lockAdminRole();
        User user = users.findForUpdate(id).orElseThrow(AdminUserService::notFound);
        if (user.getLockedAt() != null) reject(actorId, id, "USER_LOCK", reason, "Tài khoản đã bị khóa.");
        if ("SUPER_ADMIN".equals(user.getRole().getName())
                && users.countByRole_NameAndLockedAtIsNull("SUPER_ADMIN") <= 1) {
            reject(actorId, id, "USER_LOCK", reason, "Không thể khóa SUPER_ADMIN cuối cùng đang hoạt động.");
        }
        LocalDateTime now = LocalDateTime.now();
        user.setLockedAt(now);
        user.setLockedReason(reason);
        var refreshTokens = tokens.findByUser_IdAndTokenTypeAndUsedAtIsNullAndRevokedAtIsNull(id, "REFRESH");
        refreshTokens.forEach(token -> token.setRevokedAt(now));
        audit.record(actorId, "USER_LOCK", "USER", id.toString(), reason, "SUCCESS",
                Map.of("before", Map.of("status", "ACTIVE"), "after", Map.of("status", "LOCKED")));
        return AdminUserView.from(user, true, now);
    }

    @Transactional
    public AdminUserView unlock(Long actorId, Long id, String reason) {
        reason = validateReason(reason);
        users.lockAdminRole();
        // The role lock already serializes admin actions. Avoid locking the actor's
        // row before a rejected self-unlock is written in a separate transaction.
        User user = (actorId.equals(id) ? users.findById(id) : users.findForUpdate(id))
                .orElseThrow(AdminUserService::notFound);
        if (user.getLockedAt() == null) {
            reject(actorId, id, "USER_UNLOCK", reason, "Tài khoản không bị khóa.");
        }
        user.setLockedAt(null);
        user.setLockedReason(null);
        audit.record(actorId, "USER_UNLOCK", "USER", id.toString(), reason, "SUCCESS",
                Map.of("before", Map.of("status", "LOCKED"), "after", Map.of("status", "ACTIVE")));
        return AdminUserView.from(user, true, LocalDateTime.now());
    }

    private void reject(Long actorId, Long id, String action, String reason, String message) {
        audit.recordRejected(actorId, action, "USER", id.toString(), reason, Map.of("rejection", message));
        throw new ApiException(ErrorCode.CONFLICT, message);
    }

    private static String validateReason(String reason) {
        if (reason == null || reason.trim().length() < 5 || reason.trim().length() > 500) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Lý do phải có từ 5 đến 500 ký tự.");
        }
        return reason.trim();
    }

    static PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1) throw new ApiException(ErrorCode.BAD_REQUEST, "Phân trang không hợp lệ.");
        return PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng.");
    }
}
