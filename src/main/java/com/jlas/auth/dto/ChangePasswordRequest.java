package com.jlas.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class ChangePasswordRequest {
    @NotBlank
    @ToString.Exclude
    private String currentPassword;

    @NotBlank
    @Size(min = 8, max = 72)
    @ToString.Exclude
    private String password;

    @NotBlank
    @ToString.Exclude
    private String confirmPassword;

    @AssertTrue(message = "Password confirmation does not match")
    public boolean isPasswordConfirmed() {
        return password != null && password.equals(confirmPassword);
    }
}
