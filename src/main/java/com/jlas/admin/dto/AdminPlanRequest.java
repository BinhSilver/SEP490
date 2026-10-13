package com.jlas.admin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.math.BigDecimal;

/** Create or edit a Premium plan for sale. The admin enters every value; nothing is defaulted. */
@Getter
public class AdminPlanRequest extends AdminReasonRequest {
    @NotBlank(message = "Vui lòng nhập tên gói.")
    @Size(min = 2, max = 100, message = "Tên gói phải có từ 2 đến 100 ký tự.")
    private String name;

    @Size(max = 500, message = "Mô tả tối đa 500 ký tự.")
    private String description;

    @NotNull(message = "Vui lòng nhập giá.")
    @DecimalMin(value = "0", message = "Giá không được âm.")
    @DecimalMax(value = "9999999999.99", message = "Giá quá lớn.")
    @Digits(integer = 10, fraction = 2, message = "Giá tối đa 2 chữ số thập phân.")
    private BigDecimal price;

    @NotNull(message = "Vui lòng nhập số ngày.")
    @Min(value = 1, message = "Số ngày phải từ 1 đến 3650.")
    @Max(value = 3650, message = "Số ngày phải từ 1 đến 3650.")
    private Integer durationDays;

    public void setName(String name) {
        this.name = name == null ? null : name.trim();
    }

    public void setDescription(String description) {
        this.description = description == null || description.isBlank() ? null : description.trim();
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }
}
