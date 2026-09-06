package com.jlas.auth.enums;

/**
 * Loại phương thức đăng nhập của người dùng.
 */
public enum LoginType {

    /** Đăng nhập bằng email và mật khẩu. */
    LOCAL,

    /** Đăng nhập bằng tài khoản Google OAuth2. */
    GOOGLE
}
