package com.jlas.auth.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.dto.ChangePasswordRequest;
import com.jlas.auth.dto.LoginRequest;
import com.jlas.auth.dto.LoginResponse;
import com.jlas.auth.dto.RefreshTokenRequest;
import com.jlas.auth.dto.RegisterRequest;
import com.jlas.auth.dto.ResendVerificationRequest;
import com.jlas.auth.dto.UpdateProfileRequest;
import com.jlas.auth.dto.UserResponse;
import com.jlas.auth.dto.VerifyEmailRequest;
import com.jlas.auth.dto.GoogleLoginRequest;
import com.jlas.auth.dto.ForgotPasswordRequest;
import com.jlas.auth.dto.ResetPasswordRequest;
import com.jlas.auth.dto.VerifyPasswordResetOtpRequest;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.auth.service.AuthService;
import com.jlas.auth.service.AvatarUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final AvatarUploadService avatarUploadService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Registration successful. Please verify your email before logging in.", user));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        return authResponse("Login successful", authService.login(request, servletRequest.getRemoteAddr()));
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<LoginResponse>> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        return authResponse("Google login successful", authService.loginWithGoogle(request));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.ok(
                "Nếu email này thuộc một tài khoản, mã OTP đặt lại mật khẩu đã được gửi."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok("Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại."));
    }

    @PostMapping("/verify-reset-otp")
    public ResponseEntity<ApiResponse<Void>> verifyPasswordResetOtp(@Valid @RequestBody VerifyPasswordResetOtpRequest request) {
        authService.verifyPasswordResetOtp(request);
        return ResponseEntity.ok(ApiResponse.ok("Mã OTP hợp lệ."));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authResponse("Token refreshed successfully", authService.refresh(request));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<LoginResponse>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return authResponse("Email verified successfully", authService.verifyEmail(request));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
        return ResponseEntity.ok(ApiResponse.ok("Verification email has been resent"));
    }

    @GetMapping({"/me", "/profile"})
    public ResponseEntity<ApiResponse<UserResponse>> profile(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) return unauthorized();
        return ResponseEntity.ok(ApiResponse.ok("Current user profile", authService.getCurrentUser(principal.userId())));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        if (principal == null) return unauthorized();
        return ResponseEntity.ok(ApiResponse.ok(
                "Profile updated successfully", authService.updateProfile(principal.userId(), request)));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        if (principal == null) return unauthorized();
        authService.changePassword(principal.userId(), request);
        return ResponseEntity.ok(ApiResponse.ok("Password changed successfully"));
    }

    @PostMapping(value = "/avatar", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("Unauthorized"));
        }
        String url = avatarUploadService.store(principal.userId(), file);
        UpdateProfileRequest update = new UpdateProfileRequest();
        update.setAvatar(url);
        authService.updateProfile(principal.userId(), update);
        return ResponseEntity.ok(ApiResponse.ok("Avatar uploaded", Map.of("avatar", url)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        if (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank()) {
            authService.logout(request.getRefreshToken());
        }
        return ResponseEntity.ok()
                .headers(headers -> {
                    headers.add(HttpHeaders.SET_COOKIE, clearCookie("access_token", true).toString());
                    headers.add(HttpHeaders.SET_COOKIE, clearCookie("auth_status", false).toString());
                })
                .body(ApiResponse.ok("Logout successful"));
    }

    private ResponseEntity<ApiResponse<LoginResponse>> authResponse(String message, LoginResponse data) {
        ResponseCookie accessCookie = ResponseCookie.from("access_token", data.getAccessToken())
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ofSeconds(data.getExpiresIn()))
                .sameSite("Lax")
                .build();
        ResponseCookie statusCookie = ResponseCookie.from("auth_status", "1")
                .path("/")
                .maxAge(Duration.ofSeconds(data.getExpiresIn()))
                .sameSite("Lax")
                .build();
        return ResponseEntity.ok()
                .headers(headers -> {
                    headers.add(HttpHeaders.SET_COOKIE, accessCookie.toString());
                    headers.add(HttpHeaders.SET_COOKIE, statusCookie.toString());
                })
                .body(ApiResponse.ok(message, data));
    }

    private static ResponseCookie clearCookie(String name, boolean httpOnly) {
        return ResponseCookie.from(name, "")
                .httpOnly(httpOnly)
                .path("/")
                .maxAge(Duration.ZERO)
                .sameSite("Lax")
                .build();
    }

    private static <T> ResponseEntity<ApiResponse<T>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail("Unauthorized"));
    }
}
