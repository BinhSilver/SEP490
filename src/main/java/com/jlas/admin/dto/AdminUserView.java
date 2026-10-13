package com.jlas.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jlas.auth.entity.User;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AdminUserView {
    private Long id;
    private String email;
    private String username;
    private String fullName;
    private String role;
    private String loginType;
    private boolean verified;
    private boolean locked;
    private LocalDateTime lockedAt;
    private boolean premiumActive;
    private LocalDateTime premiumExpiredAt;
    private Integer creditBalance;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String lockedReason;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String premiumPlanName;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String phoneNumber;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String gender;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String japaneseLevel;

    public static final String FREE_PLAN = "FREE";

    /** Same rule as AiCallQuotaService: a non-FREE plan that has not expired yet. */
    public static boolean premiumActive(User user, LocalDateTime now) {
        return user.getPremiumPlan() != null && !FREE_PLAN.equalsIgnoreCase(user.getPremiumPlan().getName())
                && user.getPremiumExpiredAt() != null && user.getPremiumExpiredAt().isAfter(now);
    }

    public static AdminUserView from(User user, boolean detail, LocalDateTime now) {
        return AdminUserView.builder().id(user.getId()).email(user.getEmail())
                .username(user.getUsername()).fullName(user.getFullName()).role(user.getRole().getName())
                .loginType(user.getLoginType()).verified(Boolean.TRUE.equals(user.getIsVerified()))
                .locked(user.getLockedAt() != null).lockedAt(user.getLockedAt())
                .premiumActive(premiumActive(user, now))
                .premiumExpiredAt(user.getPremiumExpiredAt()).creditBalance(user.getCreditBalance())
                .createdAt(user.getCreatedAt()).lastLoginAt(user.getLastLoginAt())
                .lockedReason(detail ? user.getLockedReason() : null)
                .premiumPlanName(detail && user.getPremiumPlan() != null ? user.getPremiumPlan().getName() : null)
                .phoneNumber(detail ? user.getPhoneNumber() : null).gender(detail ? user.getGender() : null)
                .japaneseLevel(detail ? user.getJapaneseLevel() : null).build();
    }
}
