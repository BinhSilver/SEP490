package com.jlas.auth.dto;

import lombok.Data;

/**
 * Request body cho API đăng nhập.
 */
@Data
public class LoginRequest {

    /** Email tài khoản. */
    private String email;

    /** Mật khẩu (plain text – sẽ được so sánh với hash). */
    private String password;
}
