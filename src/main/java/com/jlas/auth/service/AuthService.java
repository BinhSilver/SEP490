package com.jlas.auth.service;

import com.jlas.auth.dto.LoginRequest;
import com.jlas.auth.dto.LoginResponse;
import com.jlas.auth.dto.RegisterRequest;
import com.jlas.auth.dto.UserResponse;

/**
 * Service xử lý nghiệp vụ xác thực và quản lý người dùng.
 */
public interface AuthService {

    /**
     * Đăng nhập bằng email / password.
     *
     * @param request thông tin đăng nhập
     * @return token và thông tin user
     */
    LoginResponse login(LoginRequest request);

    /**
     * Đăng ký tài khoản mới.
     *
     * @param request thông tin đăng ký
     * @return thông tin user vừa tạo
     */
    UserResponse register(RegisterRequest request);

    /**
     * Đăng xuất – vô hiệu hoá token hiện tại.
     *
     * @param token JWT token cần thu hồi
     */
    void logout(String token);
}
