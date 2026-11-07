package com.jlas.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
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

    private LocalDate dateOfBirth;

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

    /** Mã khung avatar đang dùng (vd. frame-cosmic); null = không khung. */
    private String avatarFrame;

    /** Mã màu tên đang dùng (vd. cosmic); null = màu mặc định. */
    private String nameStyle;
}
