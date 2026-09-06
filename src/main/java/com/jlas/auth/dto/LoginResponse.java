package com.jlas.auth.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Response trả về sau khi đăng nhập thành công.
 */
@Data
@Builder
public class LoginResponse {

    /** JWT access token. */
    private String accessToken;

    /** Loại token (Bearer). */
    private String tokenType;

    /** Thông tin người dùng vừa đăng nhập. */
    private UserResponse user;
}
