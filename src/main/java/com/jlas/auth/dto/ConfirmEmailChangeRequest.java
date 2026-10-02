package com.jlas.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;

@Data
public class ConfirmEmailChangeRequest {
    @NotBlank
    @Pattern(regexp = "\\d{6}")
    @ToString.Exclude
    private String oldEmailOtp;

    @NotBlank
    @Pattern(regexp = "\\d{6}")
    @ToString.Exclude
    private String newEmailOtp;
}
