package com.jlas.admin.dto;

import com.jlas.auth.entity.PremiumPlan;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A Premium plan with the number of accounts whose Premium on it is still running. */
public record AdminPlanView(Long id, String name, String description, BigDecimal price, Integer durationDays,
                            boolean active, boolean free, long activeSubscribers,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static AdminPlanView from(PremiumPlan plan, long activeSubscribers) {
        return new AdminPlanView(plan.getId(), plan.getName(), plan.getDescription(), plan.getPrice(),
                plan.getDurationDays(), Boolean.TRUE.equals(plan.getIsActive()),
                AdminUserView.FREE_PLAN.equalsIgnoreCase(plan.getName()), activeSubscribers,
                plan.getCreatedAt(), plan.getUpdatedAt());
    }
}
