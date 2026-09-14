package com.jlas.auth.service;

import com.jlas.auth.dto.LoginRequest;
import com.jlas.auth.dto.LoginResponse;
import com.jlas.auth.dto.RegisterRequest;
import com.jlas.auth.dto.UserResponse;
import com.jlas.auth.dto.RefreshTokenRequest;
import com.jlas.auth.dto.UpdateProfileRequest;
import com.jlas.auth.dto.VerifyEmailRequest;
import com.jlas.auth.dto.ResendVerificationRequest;
import com.jlas.auth.dto.ChangePasswordRequest;
import com.jlas.auth.dto.GoogleLoginRequest;
import com.jlas.auth.dto.ForgotPasswordRequest;
import com.jlas.auth.dto.ResetPasswordRequest;
import com.jlas.auth.dto.VerifyPasswordResetOtpRequest;

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
    LoginResponse login(LoginRequest request, String clientIp);

    LoginResponse login(LoginRequest request, String clientIp, String deviceInfo);

    /**
     * Đăng ký tài khoản mới.
     *
     * @param request thông tin đăng ký
     * @return thông tin user vừa tạo
     */
    UserResponse register(RegisterRequest request);

    LoginResponse refresh(RefreshTokenRequest request);

    UserResponse getCurrentUser(Long userId);

    UserResponse updateProfile(Long userId, UpdateProfileRequest request);

    LoginResponse verifyEmail(VerifyEmailRequest request);

    void resendVerification(ResendVerificationRequest request);

    void changePassword(Long userId, ChangePasswordRequest request);

    LoginResponse loginWithGoogle(GoogleLoginRequest request);

    LoginResponse loginWithGoogle(GoogleLoginRequest request, String clientIp, String deviceInfo);

    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    void verifyPasswordResetOtp(VerifyPasswordResetOtpRequest request);

    /**
     * Đăng xuất – vô hiệu hoá token hiện tại.
     *
     * @param token JWT token cần thu hồi
     */
    void logout(String token);
}
