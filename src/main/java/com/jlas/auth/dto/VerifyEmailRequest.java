package com.jlas.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class VerifyEmailRequest {
    @Email
    private String email;

    @Pattern(regexp = "\\d{6}", message = "OTP must contain exactly 6 digits")
    private String otp;

    private String token;
}
