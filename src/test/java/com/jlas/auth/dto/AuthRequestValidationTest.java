package com.jlas.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRequestValidationTest {
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("AUTH-TC-004: chấp nhận họ tên tiếng Việt có dấu")
    void registerAcceptsVietnameseFullName() {
        RegisterRequest request = validRegister();
        request.setFullName("Nguyễn Thị Ánh");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("AUTH-TC-007: từ chối họ tên có nhiều khoảng trắng liên tiếp")
    void registerRejectsConsecutiveSpacesInFullName() {
        RegisterRequest request = validRegister();
        request.setFullName("Nguyen  Van");
        assertThat(fields(validator.validate(request))).contains("fullName");
    }

    @Test
    @DisplayName("AUTH-TC-007b: từ chối họ tên bắt đầu bằng khoảng trắng")
    void registerRejectsLeadingSpaceInFullName() {
        // Fix #6a: test space đầu
        RegisterRequest request = validRegister();
        request.setFullName(" Nguyen Van");
        assertThat(fields(validator.validate(request))).contains("fullName");
    }

    @Test
    @DisplayName("AUTH-TC-007c: từ chối họ tên kết thúc bằng khoảng trắng")
    void registerRejectsTrailingSpaceInFullName() {
        // Fix #6b: test space cuối
        RegisterRequest request = validRegister();
        request.setFullName("Nguyen Van ");
        assertThat(fields(validator.validate(request))).contains("fullName");
    }

    @Test
    @DisplayName("AUTH-TC-014: từ chối mật khẩu đăng ký 7 ký tự")
    void registerRejectsSevenCharacterPassword() {
        RegisterRequest request = validRegister();
        request.setPassword("Ab@1234");
        request.setConfirmPassword("Ab@1234");
        assertThat(fields(validator.validate(request))).contains("password");
    }

    @Test
    @DisplayName("AUTH-TC-022: từ chối xác nhận mật khẩu không khớp")
    void registerRejectsMismatchedConfirmation() {
        RegisterRequest request = validRegister();
        request.setConfirmPassword("Different@123");
        assertThat(fields(validator.validate(request))).contains("passwordConfirmed");
    }

    @Test
    @DisplayName("AUTH-TC-037: OTP xác minh phải đủ 6 chữ số")
    void verifyEmailRejectsFiveDigitOtp() {
        VerifyEmailRequest request = new VerifyEmailRequest();
        request.setEmail("learner@example.com");
        request.setOtp("12345");
        assertThat(fields(validator.validate(request))).contains("otp");
    }

    @Test
    @DisplayName("AUTH-TC-043: từ chối email đăng nhập sai định dạng")
    void loginRejectsMalformedEmail() {
        LoginRequest request = new LoginRequest();
        request.setEmail("not-an-email");
        request.setPassword("Valid@123");
        assertThat(fields(validator.validate(request))).contains("email");
    }

    @Test
    @DisplayName("AUTH-TC-078: OTP reset phải đủ 6 chữ số")
    void resetOtpRejectsFiveDigits() {
        VerifyPasswordResetOtpRequest request = new VerifyPasswordResetOtpRequest();
        request.setEmail("learner@example.com");
        request.setOtp("12345");
        assertThat(fields(validator.validate(request))).contains("otp");
    }

    @Test
    @DisplayName("PROF-TC-007: từ chối số điện thoại vượt 30 ký tự")
    void profileRejectsPhoneLongerThanThirtyCharacters() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setPhoneNumber("1".repeat(31));
        assertThat(fields(validator.validate(request))).contains("phoneNumber");
    }

    @Test
    @DisplayName("PROF-TC-036: từ chối mật khẩu mới 7 ký tự")
    void changePasswordRejectsSevenCharacterPassword() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("Current@123");
        request.setPassword("Ab@1234");
        request.setConfirmPassword("Ab@1234");
        assertThat(fields(validator.validate(request))).contains("password");
    }

    private static RegisterRequest validRegister() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("learner@example.com");
        request.setPassword("Valid@123");
        request.setConfirmPassword("Valid@123");
        request.setFullName("Nguyen Van An");
        request.setJapaneseLevel("N5");
        return request;
    }

    private static Set<String> fields(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }
}
