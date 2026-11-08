package com.jlas.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Nguồn tài khoản duy nhất của toàn hệ thống.
 * Table: users
 */
@Entity
@Table(name = "users")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Vai trò của user. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_role"))
    private Role role;

    /** Gói premium hiện tại. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "premium_plan_id",
            foreignKey = @ForeignKey(name = "fk_user_premium_plan"))
    private PremiumPlan premiumPlan;

    /** Định danh đăng nhập chính. */
    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;

    /** Tên đăng nhập nếu dữ liệu cũ có dùng. */
    @Column(name = "username", length = 50, unique = true)
    private String username;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    /** LOCAL hoặc GOOGLE. */
    @Column(name = "login_type", nullable = false, length = 20)
    private String loginType;

    @Column(name = "google_id", length = 255, unique = true)
    private String googleId;

    @Column(name = "full_name", length = 150)
    private String fullName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "gender", length = 20)
    private String gender;

    /** Ngày sinh; NULL với tài khoản cũ và tài khoản Google (được hỏi khi mở video có nhãn độ tuổi). */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "japanese_level", length = 10)
    private String japaneseLevel;

    /** Ngày hết hạn premium hiện tại; NULL nếu chưa có. */
    @Column(name = "premium_expired_at")
    private LocalDateTime premiumExpiredAt;

    /** Số credit hiện tại, đọc nhanh. */
    @Column(name = "credit_balance", nullable = false)
    @Builder.Default
    private Integer creditBalance = 0;

    @Column(name = "is_verified", nullable = false)
    @Builder.Default
    private Boolean isVerified = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_reason", length = 500)
    private String lockedReason;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
