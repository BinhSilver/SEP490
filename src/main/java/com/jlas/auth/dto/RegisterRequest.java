package com.jlas.auth.dto;

import lombok.Data;

/**
 * Request body cho API đăng ký tài khoản mới.
 */
@Data
public class RegisterRequest {

    private String email;

    private String username;

    private String password;

    private String fullName;
}
