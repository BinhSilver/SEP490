package com.jlas.admin.dto;

import com.jlas.auth.entity.PremiumPlan;

import java.math.BigDecimal;

public record AdminPlanOption(Long id, String name, BigDecimal price, Integer durationDays, boolean active) {
    public static AdminPlanOption from(PremiumPlan plan) {
        return new AdminPlanOption(plan.getId(), plan.getName(), plan.getPrice(), plan.getDurationDays(),
                Boolean.TRUE.equals(plan.getIsActive()));
    }
}
