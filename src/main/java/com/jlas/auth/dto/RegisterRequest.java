package com.jlas.auth.dto;

import lombok.Data;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body cho API đăng ký tài khoản mới.
 */
@Data
public class RegisterRequest {

    @NotBlank
    @Email
    private String email;

    @Size(max = 50)
    private String username;

    @NotBlank
    @Size(min = 8, max = 72)
    private String password;

    @NotBlank
    private String confirmPassword;

    @NotBlank
    @Size(min = 2, max = 150)
    @Pattern(
            regexp = "^[\\p{IsLatin}][\\p{IsLatin}\\p{M}]*(?: [\\p{IsLatin}][\\p{IsLatin}\\p{M}]*)*$",
            message = "Full name must contain Latin letters and single spaces only"
    )
    private String fullName;

    @Size(max = 10)
    private String japaneseLevel;

    @AssertTrue(message = "Password confirmation does not match")
    public boolean isPasswordConfirmed() {
        return password != null && password.equals(confirmPassword);
    }
}
