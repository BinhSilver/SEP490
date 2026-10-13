package com.jlas.admin.service;

import com.jlas.admin.dto.AdminAuditLogView;
import com.jlas.admin.dto.AdminUserView;
import com.jlas.admin.repository.AdminAuditLogRepository;
import com.jlas.admin.repository.AdminPaymentRepository;
import com.jlas.admin.repository.AdminUserRepository;
import com.jlas.auth.repository.PremiumPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** System Admin overview: accounts, Premium, plans, payments and the latest admin actions. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardService {
    private final AdminUserRepository users;
    private final PremiumPlanRepository plans;
    private final AdminPaymentRepository payments;
    private final AdminAuditLogRepository logs;

    public record Revenue(String currency, long count, BigDecimal amount) {
    }

    public record Dashboard(long totalUsers, Map<String, Long> usersByRole, long lockedUsers, long unverifiedUsers,
                            long newUsersLast7Days, long newUsersLast30Days, long premiumActive,
                            long plansOnSale, long plansStopped, Map<String, Long> paymentsByStatus,
                            List<Revenue> paidLast30Days, List<AdminAuditLogView> recentActions) {
    }

    public Dashboard summary() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Long> byRole = new TreeMap<>();
        users.countByRoleName().forEach(row -> byRole.put((String) row[0], (Long) row[1]));
        Map<String, Long> byStatus = new TreeMap<>();
        payments.countByStatus().forEach(row -> byStatus.put((String) row[0], (Long) row[1]));
        List<Revenue> paid = payments.paidSince(now.minusDays(30)).stream()
                .map(row -> new Revenue((String) row[0], (Long) row[1], (BigDecimal) row[2])).toList();
        var sellable = plans.findAll().stream()
                .filter(p -> !AdminUserView.FREE_PLAN.equalsIgnoreCase(p.getName())).toList();
        long onSale = sellable.stream().filter(p -> Boolean.TRUE.equals(p.getIsActive())).count();
        List<AdminAuditLogView> recent = logs.findAll(PageRequest.of(0, 8,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"))).map(AdminAuditLogView::from).getContent();
        return new Dashboard(users.count(), byRole, users.countByLockedAtIsNotNull(), users.countByIsVerifiedFalse(),
                users.countByCreatedAtGreaterThanEqual(now.minusDays(7)),
                users.countByCreatedAtGreaterThanEqual(now.minusDays(30)),
                users.countActivePremium(now), onSale, sellable.size() - onSale, byStatus, paid, recent);
    }
}
