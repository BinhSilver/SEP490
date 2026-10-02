package com.jlas.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Response chứa thông tin người dùng (không gồm password).
 */
@Data
@Builder
public class UserResponse {

    private Long id;

    private Long userId;

    private String email;

    private String username;

    private String fullName;

    private String avatarUrl;

    private String avatar;

    private String phoneNumber;

    private String gender;

    private String loginType;

    private Boolean googleLinked;

    private Boolean hasPassword;

    private String role;

    private String japaneseLevel;

    private Integer creditBalance;

    private Boolean isVerified;

    private LocalDateTime lastLogin;

    private LocalDateTime premiumExpiredAt;

    private LocalDateTime createdAt;
}
