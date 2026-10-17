package com.jlas.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class AdminReasonRequest {
    @NotBlank(message = "Vui lòng nhập lý do.")
    @Size(min = 5, max = 500, message = "Lý do phải có từ 5 đến 500 ký tự.")
    private String reason;

    public void setReason(String reason) {
        this.reason = reason == null ? null : reason.trim();
    }
}
