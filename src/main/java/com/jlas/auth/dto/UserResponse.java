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

    private String email;

    private String username;

    private String fullName;

    private String avatarUrl;

    private String role;

    private String japaneseLevel;

    private LocalDateTime createdAt;
}
