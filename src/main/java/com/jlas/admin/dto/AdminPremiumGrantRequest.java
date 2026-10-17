package com.jlas.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminPremiumGrantRequest extends AdminReasonRequest {
    @NotNull(message = "Vui lòng chọn gói Premium.")
    private Long planId;

    @NotNull(message = "Vui lòng nhập số ngày.")
    @Min(value = 1, message = "Số ngày phải từ 1 đến 365.")
    @Max(value = 365, message = "Số ngày phải từ 1 đến 365.")
    private Integer durationDays;
}
