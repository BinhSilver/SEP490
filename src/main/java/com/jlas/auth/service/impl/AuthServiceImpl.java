package com.jlas.auth.service.impl;

import com.jlas.auth.dto.LoginRequest;
import com.jlas.auth.dto.LoginResponse;
import com.jlas.auth.dto.RegisterRequest;
import com.jlas.auth.dto.UserResponse;
import com.jlas.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt {@link AuthService}.
 * TODO: Điền business logic vào từng phương thức.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    // TODO: Inject UserRepository, AuthTokenRepository, PasswordEncoder, JwtUtil...

    @Override
    public LoginResponse login(LoginRequest request) {
        // TODO: Xác thực email/password → tạo JWT → trả LoginResponse
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public UserResponse register(RegisterRequest request) {
        // TODO: Kiểm tra trùng email → hash password → lưu User → trả UserResponse
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void logout(String token) {
        // TODO: Xoá / vô hiệu hoá token trong DB
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
