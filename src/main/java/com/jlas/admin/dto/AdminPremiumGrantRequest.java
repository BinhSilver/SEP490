package com.jlas.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminPremiumGrantRequest extends AdminReasonRequest {
    @NotNull(message = "Vui lòng chọn gói Premium.")
    private Long planId;

    /** [JLAS2-51] Range 1..premium.max_manual_grant_days, checked by AdminUserService (the limit is a setting). */
    @NotNull(message = "Vui lòng nhập số ngày.")
    private Integer durationDays;
}
