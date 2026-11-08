package com.jlas.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminRoleRequest extends AdminReasonRequest {
    @NotBlank(message = "Vui lòng chọn vai trò.")
    private String role;
}
