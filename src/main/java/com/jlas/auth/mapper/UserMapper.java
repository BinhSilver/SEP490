package com.jlas.auth.mapper;

import com.jlas.auth.dto.UserResponse;
import com.jlas.auth.entity.User;
import org.springframework.stereotype.Component;

/**
 * Mapper chuyển đổi giữa {@link User} entity và các DTO tương ứng.
 */
@Component
public class UserMapper {

    /**
     * Chuyển {@link User} sang {@link UserResponse} (dùng cho API response).
     *
     * @param user entity cần chuyển
     * @return DTO response
     */
    public UserResponse toResponse(User user) {
        if (user == null) return null;
        return UserResponse.builder()
                .id(user.getId())
                .userId(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .avatarUrl(user.getAvatarUrl())
                .avatar(user.getAvatarUrl())
                .phoneNumber(user.getPhoneNumber())
                .gender(user.getGender())
                .loginType(user.getLoginType())
                .googleLinked(user.getGoogleId() != null && !user.getGoogleId().isBlank())
                .hasPassword(user.getPasswordHash() != null && !user.getPasswordHash().isBlank())
                .role(user.getRole() != null ? user.getRole().getName() : null)
                .japaneseLevel(user.getJapaneseLevel())
                .creditBalance(user.getCreditBalance())
                .isVerified(user.getIsVerified())
                .lastLogin(user.getLastLoginAt())
                .premiumExpiredAt(user.getPremiumExpiredAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
