package com.jlas.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class ResetPasswordRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "OTP must contain exactly 6 digits")
    @ToString.Exclude
    private String otp;

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
