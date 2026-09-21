package com.jlas.auth.dto;

import lombok.Data;
import lombok.ToString;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body cho API đăng nhập.
 */
@Data
public class LoginRequest {

    /** Email tài khoản. */
    @NotBlank
    @Email
    private String email;

    /** Mật khẩu (plain text – sẽ được so sánh với hash). */
    @NotBlank
    @ToString.Exclude
    private String password;
}
