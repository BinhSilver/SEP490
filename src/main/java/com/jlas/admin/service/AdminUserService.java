package com.jlas.admin.service;

import com.jlas.admin.dto.AdminUserView;
import com.jlas.admin.repository.AdminUserRepository;
import com.jlas.auth.entity.PremiumPlan;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.AuthTokenRepository;
import com.jlas.auth.repository.PremiumPlanRepository;
import com.jlas.auth.repository.RoleRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.notification.service.UserNotificationPort;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {
    /** Roles a System Admin may assign: learner, content creator, moderator, System Admin. */
    static final Set<String> ASSIGNABLE_ROLES = Set.of("LEARNER", "CONTENT_CREATOR", "MODERATOR", "SUPER_ADMIN");
    static final int MAX_GRANT_DAYS = 365;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final AdminUserRepository users;
    private final AuthTokenRepository tokens;
    private final RoleRepository roles;
    private final PremiumPlanRepository plans;
    private final AdminAuditService audit;
    private final UserNotificationPort notifications;

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
            if (premium != null) {
                // Same rule as the rest of the app: a non-FREE plan that has not expired yet.
                var plan = root.join("premiumPlan", JoinType.LEFT);
                Predicate active = cb.and(cb.isNotNull(plan.get("id")),
                        cb.notEqual(cb.upper(plan.get("name")), AdminUserView.FREE_PLAN),
                        cb.greaterThan(root.get("premiumExpiredAt"), now));
                filters.add(premium ? active : cb.not(active));
            }
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

    @Transactional
    public AdminUserView changeRole(Long actorId, Long id, String roleName, String reason) {
        reason = validateReason(reason);
        if (roleName == null || !ASSIGNABLE_ROLES.contains(roleName)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Vai trò không hợp lệ.");
        }
        // Reject before locking the actor row: the separate audit transaction references that row.
        if (actorId.equals(id)) reject(actorId, id, "USER_ROLE_CHANGE", reason, "Không thể tự đổi vai trò của mình.");
        users.lockAdminRole();
        User user = users.findForUpdate(id).orElseThrow(AdminUserService::notFound);
        String before = user.getRole().getName();
        if (before.equals(roleName)) reject(actorId, id, "USER_ROLE_CHANGE", reason, "Người dùng đã có vai trò này.");
        if ("SUPER_ADMIN".equals(before) && user.getLockedAt() == null
                && users.countByRole_NameAndLockedAtIsNull("SUPER_ADMIN") <= 1) {
            reject(actorId, id, "USER_ROLE_CHANGE", reason, "Không thể hạ quyền SUPER_ADMIN cuối cùng đang hoạt động.");
        }
        Role role = roles.findByName(roleName)
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_REQUEST, "Vai trò " + roleName + " chưa có trong hệ thống."));
        user.setRole(role);
        // Authorities are rebuilt from the database on every request, so the change applies immediately.
        audit.record(actorId, "USER_ROLE_CHANGE", "USER", id.toString(), reason, "SUCCESS",
                Map.of("before", Map.of("role", before), "after", Map.of("role", roleName)));
        return AdminUserView.from(user, true, LocalDateTime.now());
    }

    /**
     * Grants Premium without a payment. Days stack on top of an active Premium period;
     * an expired or FREE account starts from now. No payment row is created.
     */
    @Transactional
    public AdminUserView grantPremium(Long actorId, Long id, Long planId, Integer durationDays, String reason) {
        reason = validateReason(reason);
        if (durationDays == null || durationDays < 1 || durationDays > MAX_GRANT_DAYS) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Số ngày phải từ 1 đến " + MAX_GRANT_DAYS + ".");
        }
        PremiumPlan plan = planId == null ? null : plans.findById(planId).orElse(null);
        if (plan == null || AdminUserView.FREE_PLAN.equalsIgnoreCase(plan.getName())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Vui lòng chọn một gói Premium hợp lệ.");
        }
        if (!Boolean.TRUE.equals(plan.getIsActive())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Gói này đã ngừng bán, hãy chọn gói đang bán.");
        }
        if (actorId.equals(id)) reject(actorId, id, "PREMIUM_GRANT", reason, "Không thể tự cấp Premium cho mình.");
        User user = users.findForUpdate(id).orElseThrow(AdminUserService::notFound);
        LocalDateTime now = LocalDateTime.now();
        boolean active = AdminUserView.premiumActive(user, now);
        LocalDateTime expiresAt = (active ? user.getPremiumExpiredAt() : now).plusDays(durationDays);
        Map<String, Object> before = premiumSnapshot(user, active);
        user.setPremiumPlan(plan);
        user.setPremiumExpiredAt(expiresAt);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("before", before);
        changes.put("after", premiumSnapshot(user, true));
        changes.put("durationDays", durationDays);
        changes.put("stacked", active);
        audit.record(actorId, "PREMIUM_GRANT", "USER", id.toString(), reason, "SUCCESS", changes);
        notifications.notifyUser(id, "PREMIUM", "Bạn được cấp Premium",
                "Gói " + plan.getName() + " có hiệu lực đến " + DATE.format(expiresAt) + ".", "/profile",
                "PREMIUM_GRANT_" + expiresAt);
        return AdminUserView.from(user, true, now);
    }

    private static Map<String, Object> premiumSnapshot(User user, boolean active) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("plan", user.getPremiumPlan() == null ? null : user.getPremiumPlan().getName());
        value.put("expiresAt", user.getPremiumExpiredAt() == null ? null : user.getPremiumExpiredAt().toString());
        value.put("active", active);
        return value;
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
