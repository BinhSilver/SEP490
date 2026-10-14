package com.jlas.auth.mapper;

import com.jlas.auth.dto.UserResponse;
import com.jlas.auth.entity.User;
import com.jlas.game.repository.AvatarFrameJdbcRepository.Style;
import com.jlas.game.service.AvatarFrameService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Mapper chuyển đổi giữa {@link User} entity và các DTO tương ứng.
 */
@Slf4j
@Component
public class UserMapper {

    private final ObjectProvider<AvatarFrameService> frames;

    public UserMapper(ObjectProvider<AvatarFrameService> frames) {
        this.frames = frames;
    }

    /**
     * Chuyển {@link User} sang {@link UserResponse} (dùng cho API response).
     *
     * @param user entity cần chuyển
     * @return DTO response
     */
    public UserResponse toResponse(User user) {
        if (user == null) return null;
        Style style = profileStyle(user.getId());
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
                .role(user.getRole() != null ? com.jlas.auth.security.Roles.canonical(user.getRole().getName()) : null)
                .japaneseLevel(user.getJapaneseLevel())
                .creditBalance(user.getCreditBalance())
                .isVerified(user.getIsVerified())
                .lastLogin(user.getLastLoginAt())
                .premiumExpiredAt(user.getPremiumExpiredAt())
                .createdAt(user.getCreatedAt())
                .avatarFrame(style.frameCode())
                .nameStyle(style.nameStyle())
                .build();
    }

    /** Khung avatar + màu tên chỉ để trang trí: lỗi đọc không được làm hỏng đăng nhập hay /me. */
    private Style profileStyle(Long userId) {
        AvatarFrameService service = userId == null ? null : frames.getIfAvailable();
        if (service == null) return Style.NONE;
        try {
            return service.style(userId);
        } catch (DataAccessException ex) {
            log.warn("Không đọc được khung avatar của user {}: {}", userId, ex.getMessage());
            return Style.NONE;
        }
    }
}
