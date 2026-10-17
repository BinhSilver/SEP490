package com.jlas.admin.service;

import com.jlas.admin.dto.AdminPlanRequest;
import com.jlas.admin.dto.AdminPlanView;
import com.jlas.admin.dto.AdminUserView;
import com.jlas.admin.repository.AdminUserRepository;
import com.jlas.auth.entity.PremiumPlan;
import com.jlas.auth.repository.PremiumPlanRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Premium plans for sale. Plans are never deleted (users and payments reference them): a plan is
 * stopped instead. Editing a plan only affects future purchases; payments keep their own snapshot and
 * running Premium periods keep their expiry. FREE is the built-in plan for free accounts and is read-only.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPlanService {
    private static final String TARGET = "PREMIUM_PLAN";

    private final PremiumPlanRepository plans;
    private final AdminUserRepository users;
    private final AdminAuditService audit;

    public List<AdminPlanView> list() {
        Map<Long, Long> running = new HashMap<>();
        users.countRunningByPlan(LocalDateTime.now()).forEach(row -> running.put((Long) row[0], (Long) row[1]));
        return plans.findAll(Sort.by("price", "id")).stream()
                .map(plan -> AdminPlanView.from(plan, running.getOrDefault(plan.getId(), 0L))).toList();
    }

    @Transactional
    public AdminPlanView create(Long actorId, AdminPlanRequest request) {
        users.lockAdminRole(); // serializes plan writes so the name check below cannot race
        if (nameTaken(request.getName(), null)) {
            reject(actorId, null, "PREMIUM_PLAN_CREATE", request.getReason(), "Tên gói đã tồn tại.");
        }
        PremiumPlan plan = plans.save(PremiumPlan.builder().name(request.getName())
                .description(request.getDescription()).price(request.getPrice())
                .durationDays(request.getDurationDays()).isActive(true).build());
        audit.record(actorId, "PREMIUM_PLAN_CREATE", TARGET, plan.getId().toString(), request.getReason(), "SUCCESS",
                Map.of("after", snapshot(plan)));
        return AdminPlanView.from(plan, 0);
    }

    @Transactional
    public AdminPlanView update(Long actorId, Long id, AdminPlanRequest request) {
        users.lockAdminRole();
        PremiumPlan plan = editable(actorId, id, "PREMIUM_PLAN_UPDATE", request.getReason());
        if (nameTaken(request.getName(), id)) {
            reject(actorId, id, "PREMIUM_PLAN_UPDATE", request.getReason(), "Tên gói đã tồn tại.");
        }
        Map<String, Object> before = snapshot(plan);
        plan.setName(request.getName());
        plan.setDescription(request.getDescription());
        plan.setPrice(request.getPrice());
        plan.setDurationDays(request.getDurationDays());
        Map<String, Object> after = snapshot(plan);
        if (before.equals(after)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không có thay đổi nào để lưu.");
        }
        audit.record(actorId, "PREMIUM_PLAN_UPDATE", TARGET, id.toString(), request.getReason(), "SUCCESS",
                Map.of("before", before, "after", after));
        return view(plan);
    }

    /** Starts or stops selling a plan. Accounts already on it keep their Premium until it expires. */
    @Transactional
    public AdminPlanView setActive(Long actorId, Long id, boolean active, String reason) {
        users.lockAdminRole();
        String action = active ? "PREMIUM_PLAN_ACTIVATE" : "PREMIUM_PLAN_DEACTIVATE";
        PremiumPlan plan = editable(actorId, id, action, reason);
        if (Boolean.TRUE.equals(plan.getIsActive()) == active) {
            reject(actorId, id, action, reason, active ? "Gói đang được bán." : "Gói đã ngừng bán.");
        }
        plan.setIsActive(active);
        audit.record(actorId, action, TARGET, id.toString(), reason, "SUCCESS",
                Map.of("before", Map.of("active", !active), "after", Map.of("active", active)));
        return view(plan);
    }

    private PremiumPlan editable(Long actorId, Long id, String action, String reason) {
        PremiumPlan plan = plans.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy gói Premium."));
        if (AdminUserView.FREE_PLAN.equalsIgnoreCase(plan.getName())) {
            reject(actorId, id, action, reason, "Gói FREE là gói mặc định của tài khoản miễn phí, không thể sửa.");
        }
        return plan;
    }

    // ponytail: scans every plan; there are only a handful, add a repository query if that changes.
    private boolean nameTaken(String name, Long exceptId) {
        return AdminUserView.FREE_PLAN.equalsIgnoreCase(name) || plans.findAll().stream()
                .anyMatch(p -> !Objects.equals(p.getId(), exceptId) && p.getName().equalsIgnoreCase(name));
    }

    private AdminPlanView view(PremiumPlan plan) {
        long running = users.countRunningByPlan(LocalDateTime.now()).stream()
                .filter(row -> plan.getId().equals(row[0])).mapToLong(row -> (Long) row[1]).sum();
        return AdminPlanView.from(plan, running);
    }

    private static Map<String, Object> snapshot(PremiumPlan plan) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", plan.getName());
        value.put("description", plan.getDescription());
        // Plain string so 10 and 10.00 compare equal and the audit JSON shows the exact amount.
        value.put("price", plan.getPrice() == null ? null : plan.getPrice().stripTrailingZeros().toPlainString());
        value.put("durationDays", plan.getDurationDays());
        return value;
    }

    private void reject(Long actorId, Long id, String action, String reason, String message) {
        audit.recordRejected(actorId, action, TARGET, id == null ? null : id.toString(), reason,
                Map.of("rejection", message));
        throw new ApiException(ErrorCode.CONFLICT, message);
    }
}
